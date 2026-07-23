package com.aliothmoon.maameow.gameview

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.aliothmoon.maameow.domain.service.MaaCompositionService
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * FlowOS 对话内「游戏虚拟屏加载」服务端（fork 专属）：127.0.0.1:8831，
 * 实现 floai 分身 ControlServer 协议子集（/displays /frame /tap /swipe）+ /size /status /stop。
 *
 * 帧经 RemoteService.setMonitorSurface tee 进自有 ImageReader——不碰 MaaCore 视觉的
 * native capturer 背板；注入复用 RemoteService.touchDown/Move/Up（远端自动路由到当前 VD）。
 * 与 MAA 自家「画面监视」页共用 monitorSurface 槽位，两者同开时后设者生效（已知限制）。
 */
class GameViewServer(private val composition: MaaCompositionService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "gameview-conn" }
    }
    private val gestureMutex = Mutex()   // 注入手势串行化，避免两条滑动交错
    @Volatile private var started = false
    @Volatile private var latestPng: ByteArray? = null
    @Volatile private var lastFrameMs = 0L
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null

    fun start() {
        if (started) return
        started = true
        // 屏生灭驱动帧 tee 生命周期
        scope.launch {
            composition.activeVirtualDisplayId.collect { id ->
                if (id >= 0) startFrameTee() else stopFrameTee()
            }
        }
        Thread({ serveLoop() }, "gameview-http").apply { isDaemon = true }.start()
        Log.i(TAG, "GameViewServer listening on 127.0.0.1:$PORT")
    }

    private fun serveLoop() {
        val server = try {
            ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            Log.e(TAG, "bind :$PORT failed", e); return
        }
        while (true) {
            val sock = runCatching { server.accept() }.getOrNull() ?: continue
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
                val vdId = composition.activeVirtualDisplayId.value
                val alive = vdId >= 0 && RemoteServiceManager.getInstanceOrNull() != null
                json(out, if (alive) """{"displays":[$vdId]}""" else """{"displays":[]}""")
            }

            "/size" -> {
                val r = composition.displayResolution.value
                json(out, """{"w":${r.width},"h":${r.height}}""")
            }

            "/frame" -> latestPng?.let { bytes(out, it, "image/png") } ?: status404(out)

            "/tap" -> {
                val x = req.query["x"]?.toIntOrNull()
                val y = req.query["y"]?.toIntOrNull()
                if (x == null || y == null) { status404(out); return }
                scope.launch {
                    gestureMutex.withLock {
                        runCatching {
                            RemoteServiceManager.getInstanceOrNull()?.apply {
                                touchDown(x, y); delay(40); touchUp(x, y)
                            }
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
                        runCatching {
                            RemoteServiceManager.getInstanceOrNull()?.apply {
                                touchDown(x1, y1)
                                val steps = (ms / 16).toInt().coerceAtLeast(2)
                                for (i in 1..steps) {
                                    delay(16)
                                    touchMove(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
                                }
                                touchUp(x2, y2)
                            }
                        }.onFailure { Log.w(TAG, "swipe: ${it.message}") }
                    }
                }
                json(out, "{}")
            }

            "/status" -> json(out, """{"state":"${composition.state.value.name}"}""")

            "/stop" -> {
                val ok = runBlocking { withTimeoutOrNull(65_000) { composition.stop() } }
                json(out, """{"ok":${ok is MaaCompositionService.StopResult.Success}}""")
            }

            else -> status404(out)
        }
    }

    // ── 帧 tee：monitorSurface → ImageReader → 最新帧 PNG ──

    @Synchronized
    private fun startFrameTee() {
        if (reader != null) return
        val r = composition.displayResolution.value
        val t = HandlerThread("gameview-frames").apply { start() }
        readerThread = t
        val ir = ImageReader.newInstance(r.width, r.height, PixelFormat.RGBA_8888, 3)
        ir.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameMs < FRAME_MIN_INTERVAL_MS) return@setOnImageAvailableListener   // 节流，finally 保证 close
                val plane = img.planes[0]
                val w = img.width; val h = img.height
                val rowPx = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (rowPx != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                val bos = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.PNG, 100, bos)
                latestPng = bos.toByteArray()
                lastFrameMs = now
            } catch (e: Exception) {
                Log.w(TAG, "frame: ${e.message}")
            } finally {
                img.close()
            }
        }, Handler(t.looper))
        reader = ir
        runCatching { RemoteServiceManager.getInstanceOrNull()?.setMonitorSurface(ir.surface) }
            .onFailure { Log.w(TAG, "setMonitorSurface: ${it.message}") }
    }

    @Synchronized
    private fun stopFrameTee() {
        if (reader == null) return
        runCatching { RemoteServiceManager.getInstanceOrNull()?.setMonitorSurface(null) }
        runCatching { reader?.close() }; reader = null
        readerThread?.quitSafely(); readerThread = null
        latestPng = null
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
        private const val FRAME_MIN_INTERVAL_MS = 150L   // ~6fps，够观看/接管，压住 PNG 编码开销
    }
}
