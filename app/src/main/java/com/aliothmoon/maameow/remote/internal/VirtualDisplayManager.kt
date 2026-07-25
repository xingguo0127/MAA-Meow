package com.aliothmoon.maameow.remote.internal

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import com.aliothmoon.maameow.bridge.NativeBridgeLib
import com.aliothmoon.maameow.constant.AndroidVersions
import com.aliothmoon.maameow.constant.DefaultDisplayConfig
import com.aliothmoon.maameow.constant.DefaultDisplayConfig.VD_NAME
import com.aliothmoon.maameow.third.Ln
import com.aliothmoon.maameow.third.wrappers.ServiceManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


object VirtualDisplayManager {

    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT: Int = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    private const val VD_SYSTEM_DECORATIONS = false
    private const val VD_DESTROY_CONTENT = true

    const val DISPLAY_NONE = -1

    data class DisplayConfig(
        val width: Int = DefaultDisplayConfig.WIDTH,
        val height: Int = DefaultDisplayConfig.HEIGHT,
        val dpi: Int = DefaultDisplayConfig.DPI
    )

    private val state = AtomicInteger(STATE_IDLE)
    private val config = AtomicReference(DisplayConfig())
    private val displayId = AtomicInteger(DISPLAY_NONE)
    private val virtualDisplay = AtomicReference<VirtualDisplay?>()

    private val monitorSurface = AtomicReference<Surface?>()

    /**
     * 回收前抓下的「最终帧」PNG(fork 专属)。VD 释放后 arknights Activity 被销毁、帧缓冲变黑,
     * floai 的 live_display_card 若在回收后才组合就抓不到画面(→ 卡显深色底=「黑屏」)。这里在 [stop]
     * 释放 VD 前主动抓一帧留存,[GameViewServer] 在 VD 已无(displayId<0)时用它兜底出帧,让卡片定格最后一帧。
     * 新任务 [startInternal] 时清掉,不串上一局。
     */
    private val finalFramePng = AtomicReference<ByteArray?>()

    /** VD 已回收后 GameViewServer /frame 的兜底数据源:最后一帧 PNG(无则 null)。 */
    fun getFinalFramePng(): ByteArray? = finalFramePng.get()

    fun setMonitorSurface(surface: Surface?) {
        val old = monitorSurface.getAndSet(surface)
        if (old != null && old != surface) {
            old.release()
            Ln.i("Old monitor surface released")
        }
        Ln.i("setMonitorSurface: old=${old != null}, new=${surface != null}")
    }

    fun start(): Int {
        if (!state.compareAndSet(STATE_IDLE, STATE_CAPTURING)) {
            Ln.w("start: already capturing")
            return displayId.get()
        }
        return startInternal()
    }

    fun stop() {
        if (!state.compareAndSet(STATE_CAPTURING, STATE_IDLE)) {
            return
        }
        captureFinalFrame()   // 释放 VD 前先抓最后一帧,供回收后 floai 卡定格(见 finalFramePng)
        releaseResources()
        monitorSurface.getAndSet(null)?.release()
        Ln.i("VirtualDisplayManager stopped")
    }

    /** 释放前抓当前帧缓冲编码成 PNG 存入 [finalFramePng];失败不阻断回收(顶多没有定格帧)。 */
    private fun captureFinalFrame() {
        runCatching {
            val bmp: Bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: return
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            finalFramePng.set(bos.toByteArray())
            Ln.i("VD final frame captured: ${bos.size()} B (freeze-frame for floai after reclaim)")
        }.onFailure { Ln.w("captureFinalFrame failed: ${it.message}") }
    }

    fun restart() {
        if (state.get() != STATE_CAPTURING) {
            return
        }
        releaseResources()
        startInternal()
    }

    fun setResolution(width: Int, height: Int, dpi: Int = config.get().dpi) {
        val newConfig = DisplayConfig(width, height, dpi)
        val oldConfig = config.getAndSet(newConfig)
        if (state.get() == STATE_CAPTURING && oldConfig != newConfig) {
            Ln.i("Resolution changed: ${oldConfig.width}x${oldConfig.height} -> ${width}x${height}, restart")
            restart()
        }
    }

    fun getDisplayId(): Int = displayId.get()

    /** 当前虚拟屏配置分辨率(w/h/dpi)。GameViewServer /size 数据源(fork 专属)——
     *  与 setupNativeCapturer(cfg.width, cfg.height) 一致,故等于帧缓冲尺寸,可直接用于坐标映射。 */
    fun getResolution(): DisplayConfig = config.get()

    private fun startInternal(): Int {
        try {
            finalFramePng.set(null)   // 新一局:清掉上一局的定格帧,别串画面
            val cfg = config.get()
            val surface = NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)
            createVirtualDisplay(surface, cfg)

            Ln.i("VirtualDisplayManager started, displayId=${displayId.get()}")
            return displayId.get()
        } catch (e: Exception) {
            Ln.e("VirtualDisplayManager start failed", e)
            state.set(STATE_IDLE)
            return DISPLAY_NONE
        }
    }

    private fun releaseResources() {
        virtualDisplay.getAndSet(null)?.release()
        NativeBridgeLib.releaseNativeCapturer()
        displayId.set(DISPLAY_NONE)
    }

    private fun createVirtualDisplay(surface: Surface, cfg: DisplayConfig) {
        val flags = buildDisplayFlags()
        val wm = ServiceManager.getWindowManager()
        val physicalRotation = runCatching { wm.rotation }.getOrDefault(-1)
        Ln.i("Physical display rotation: $physicalRotation")

        val vd = ServiceManager.getDisplayManager()
            .createNewVirtualDisplay(
                VD_NAME,
                cfg.width,
                cfg.height,
                cfg.dpi,
                surface,
                flags
            )
        virtualDisplay.set(vd)
        val vdId = vd.display.displayId
        displayId.set(vdId)

        val d = vd.display
        Ln.i(
            "VD created: id=$vdId" +
            ", configured=${cfg.width}x${cfg.height}" +
            ", actual=${d.width}x${d.height}" +
            ", rotation=${d.rotation}" +
            ", flags=0x${flags.toString(16)}"
        )

        if (d.rotation != Surface.ROTATION_0) {
            // 所有旋转非零的情况都先尝试 freezeRotation
            runCatching {
                wm.freezeRotation(vdId, Surface.ROTATION_0)
                Ln.i("freezeRotation done, post-freeze rotation=${vd.display.rotation}")
            }.onFailure { e -> Ln.w("freezeRotation failed: ${e.message}") }

            if (physicalRotation == Surface.ROTATION_0) {
                // 物理屏处于自然方向（rotation=0）而 VD 却有旋转角，
                // 这是横屏原生设备如AYN Odin2的典型特征：
                // 此类设备的定制 ROM 对二级显示调 freezeRotation 无效，
                // 额外调 setForcedDisplaySize 强制 VD 向内部 app 上报横屏尺寸。
                Ln.w(
                    "Landscape-native device detected (physRot=0, vdRot=${d.rotation}), " +
                    "applying setForcedDisplaySize"
                )
                runCatching {
                    wm.setForcedDisplaySize(vdId, cfg.width, cfg.height)
                    Ln.i("setForcedDisplaySize(${cfg.width}x${cfg.height}) applied")
                }.onFailure { e -> Ln.w("setForcedDisplaySize failed: ${e.message}") }
            }
        }
    }

    private fun buildDisplayFlags(): Int {
        var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                or VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH)

        if (VD_DESTROY_CONTENT) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        }
        if (VD_SYSTEM_DECORATIONS) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                        or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
            }
        }
        return flags
    }
}
