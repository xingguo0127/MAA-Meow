package com.aliothmoon.maameow.gameview

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.aliothmoon.maameow.bridge.NativeBridgeLib
import com.aliothmoon.maameow.maa.InputControlUtils
import com.aliothmoon.maameow.remote.MaaCoreManager
import com.aliothmoon.maameow.remote.internal.VirtualDisplayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * FlowOS 对话内「游戏虚拟屏加载」服务端(fork 专属):127.0.0.1:8831,
 * 实现 floai 分身 ControlServer 协议子集(/displays /frame /tap /swipe)+ /size /status /stop。
 *
 * ★ 跑在 `:service`(Shizuku)/`:root_service`(Root) 远端进程内——由 [RemoteServiceImpl] 直接
 * new + start(),不走 Koin/Application。该进程以 shell/root 身份运行,与 App uid 进程组分离,
 * **天然不被 cached-app-freezer 冻结**,故 MAA 退后台后 floai 仍能连 8831 取帧/接管/停任务
 * (根治 PR #55 主进程被冻结即失效的问题)。
 *
 * 进程内直调:帧 = [NativeBridgeLib.getFrameBufferBitmap](标准 ARGB Bitmap,无 PR #55 那个
 * HardwareBuffer 10-bit 颜色错乱);注入 = [InputControlUtils];状态/停止 = [MaaCoreManager]。
 */
class GameViewServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "gameview-conn" }
    }
    private val gestureMutex = Mutex()   // 注入手势串行化,避免两条滑动交错

    @Volatile private var started = false
    @Volatile private var server: ServerSocket? = null

    // 帧缓存:pull-based(floai 按 ~6fps 轮询),getFrameCount 判新帧 + 节流压 PNG 编码开销
    private val frameLock = Any()
    @Volatile private var latestPng: ByteArray? = null
    private var lastFrameCount = -1L
    private var lastEncodeMs = 0L

    fun start() {
        if (started) return
        started = true
        Thread({ serveLoop() }, "gameview-http").apply { isDaemon = true }.start()
        Log.i(TAG, "GameViewServer listening on 127.0.0.1:$PORT (remote service process)")
    }

    fun stop() {
        started = false
        runCatching { server?.close() }   // 打断 accept()
        runCatching { scope.cancel() }
        runCatching { pool.shutdownNow() }
    }

    private fun serveLoop() {
        val srv = try {
            // 显式 bind IPv4:getLoopbackAddress() 在部分设备返回 ::1(只监听 IPv6),
            // floai 侧连 IPv4 127.0.0.1 会 ConnectException。约定用 IPv4(与 8830 分身一致)。
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.e(TAG, "bind :$PORT failed", e); return
        }
        server = srv
        while (started) {
            val sock = runCatching { srv.accept() }.getOrNull() ?: continue
            pool.execute {
                runCatching {
                    sock.use { s ->
                        val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                        val req = parseRequestLine(line) ?: return@use
                        handle(req, s.getOutputStream())
                    }
                }.onFailure { Log.w(TAG, "conn: ${it.message}") }
            }
        }
    }

    private fun handle(req: HttpRequestLine, out: OutputStream) {
        when (req.path) {
            "/displays" -> {
                val id = VirtualDisplayManager.getDisplayId()
                json(out, if (id >= 0) """{"displays":[$id]}""" else """{"displays":[]}""")
            }

            "/size" -> {
                val r = VirtualDisplayManager.getResolution()
                json(out, """{"w":${r.width},"h":${r.height}}""")
            }

            "/frame" -> framePng()?.let { bytes(out, it, "image/png") } ?: status404(out)

            "/tap" -> {
                val x = req.query["x"]?.toIntOrNull()
                val y = req.query["y"]?.toIntOrNull()
                if (x == null || y == null) { status404(out); return }
                scope.launch {
                    gestureMutex.withLock {
                        val displayId = VirtualDisplayManager.getDisplayId()
                        if (displayId >= 0) runCatching {
                            InputControlUtils.down(x, y, displayId); delay(40); InputControlUtils.up(x, y, displayId)
                        }.onFailure { Log.w(TAG, "tap: ${it.message}") }
                    }
                }
                json(out, "{}")
            }

            "/swipe" -> {
                val x1 = req.query["x1"]?.toIntOrNull(); val y1 = req.query["y1"]?.toIntOrNull()
                val x2 = req.query["x2"]?.toIntOrNull(); val y2 = req.query["y2"]?.toIntOrNull()
                val ms = (req.query["ms"]?.toLongOrNull() ?: 300L).coerceIn(50, 3000)
                if (x1 == null || y1 == null || x2 == null || y2 == null) { status404(out); return }
                scope.launch {
                    gestureMutex.withLock {
                        val displayId = VirtualDisplayManager.getDisplayId()
                        if (displayId >= 0) runCatching {
                            InputControlUtils.down(x1, y1, displayId)
                            val steps = (ms / 16).toInt().coerceAtLeast(2)
                            for (i in 1..steps) {
                                delay(16)
                                InputControlUtils.move(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, displayId)
                            }
                            InputControlUtils.up(x2, y2, displayId)
                        }.onFailure { Log.w(TAG, "swipe: ${it.message}") }
                    }
                }
                json(out, "{}")
            }

            "/status" -> {
                // 5 态状态机在主进程,远端还原不了;floai 只用 /status 判「接管时要不要弹确认停止」,
                // 只需 running/idle 二元(见 MaaScreenSource.fetchStatus)。用 Core Running() 合成。
                val running = runCatching { MaaCoreManager.maaService.Running() }.getOrDefault(false)
                json(out, """{"state":"${if (running) "RUNNING" else "IDLE"}"}""")
            }

            "/stop" -> {
                // 远端硬停 native(AsstStop),达接管核心目的(不再自动点击)。已停则视作成功
                // (对齐 MaaCompositionService.stop() 的 !Running 早返回)。主进程状态机收尾靠 Core
                // 停止回调解冻后收敛(见设计「stop 一致性」)。
                val ok = runCatching {
                    val maa = MaaCoreManager.maaService
                    if (!maa.Running()) true else maa.Stop()
                }.getOrDefault(false)
                json(out, """{"ok":$ok}""")
            }

            else -> status404(out)
        }
    }

    // ── 帧:进程内直读最新帧 buffer → PNG ──

    private fun framePng(): ByteArray? = synchronized(frameLock) {
        if (VirtualDisplayManager.getDisplayId() < 0) return null   // 无活动虚拟屏
        val fc = NativeBridgeLib.getFrameCount()
        val now = SystemClock.elapsedRealtime()
        val cached = latestPng
        // 无新帧 或 节流窗口内 → 复用缓存(不重复编码,不抢 core 抓帧)
        if (cached != null && (fc == lastFrameCount || now - lastEncodeMs < FRAME_MIN_INTERVAL_MS)) {
            return cached
        }
        val bmp = NativeBridgeLib.getFrameBufferBitmap() ?: return cached
        try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            latestPng = bos.toByteArray()
            lastFrameCount = fc
            lastEncodeMs = now
        } catch (e: Exception) {
            Log.w(TAG, "frame encode: ${e.message}")
        } finally {
            bmp.recycle()
        }
        return latestPng
    }

    // ── HTTP 响应小工具 ──

    private fun json(out: OutputStream, body: String) = bytes(out, body.toByteArray(), "application/json")

    private fun bytes(out: OutputStream, body: ByteArray, type: String) {
        out.write(
            "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray()
        )
        out.write(body); out.flush()
    }

    private fun status404(out: OutputStream) {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    companion object {
        private const val TAG = "GameViewServer"
        private const val PORT = 8831
        private const val FRAME_MIN_INTERVAL_MS = 150L   // ~6fps,够观看/接管,压住 PNG 编码开销
    }
}
