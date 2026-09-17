package com.e7.shop.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import com.e7.shop.bot.Humanizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * 设备 IO：截图与手势执行（从 ShopAccessibilityService 抽出）。
 *
 * 职责边界：本类只做"把屏幕取下来"和"把手指按下去"两件事，
 * **不认识商店、不认识商品、不做任何决策**。决策在 BotEngine/AiBotEngine，
 * 安全闸门在 ClickGate —— 三者互不越界。
 *
 * 为什么单独成类：截图与手势是 Service 的"外设驱动"，与生命周期、会话管理、
 * 悬浮窗、诊断都无关。抽出来后可以独立演进（例如未来换 SurfaceFlinger 抓屏），
 * 不影响任何业务逻辑。
 */
class DeviceIo(
    private val service: AccessibilityService,
    private val executor: ExecutorService,
    private val humanizer: Humanizer,
    private val speedMult: () -> Int,
    private val filesDir: File,
    private val onShotResult: (ok: Boolean) -> Unit
) {

    private var lastDebugSaveAt = 0L

    /**
     * 截取当前屏幕。
     *
     * 失败返回 null —— 调用方必须按 fail-closed 处理（不买、不刷新）。
     * 同时通过 [onShotResult] 上报健康度，供悬浮窗显示"截图正常/失败"。
     */
    fun screenshot(): Bitmap? {
        val bmp = if (Build.VERSION.SDK_INT >= 30) takeScreenshotInternal() else null
        onShotResult(bmp != null)
        // 调试 PNG 改为**时间节流**（≥8 秒一张）：旧版每 5 帧压缩一张 2800×1272 PNG，
        // 在机器人线程上耗时数百毫秒，既拖慢循环又干扰「连续两帧无变化」的稳定判定
        val now = System.currentTimeMillis()
        if (bmp != null && now - lastDebugSaveAt > DEBUG_SAVE_INTERVAL_MS) {
            lastDebugSaveAt = now
            try {
                FileOutputStream(File(filesDir, DEBUG_FRAME_NAME)).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 80, fos)
                }
            } catch (e: Exception) {
                // 调试帧写不出去绝不影响机器人：它只是排查用的副产品
                Log.w(TAG, "debug frame not saved: " + e.message)
            }
        }
        return bmp
    }

    private fun takeScreenshotInternal(): Bitmap? {
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer: HardwareBuffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        out = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                    } catch (e: Exception) {
                        // 硬件缓冲拷贝失败：保持 out = null，由调用方走 fail-closed
                        Log.w(TAG, "screenshot buffer copy failed: " + e.message)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot onFailure code=$errorCode")
                    latch.countDown()
                }
            }
        )
        if (!latch.await(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            Log.w(TAG, "takeScreenshot timed out after $SCREENSHOT_TIMEOUT_SEC s")
        }
        return out
    }

    /**
     * 决策性点击（所有决策点击的唯一出口）。
     *
     * 拟人化：在**安全范围内**加极小随机偏移（±offsetPx，硬上限 4px）。
     * 旧版这里完全没有偏移（offsetPoint 从未被调用 → 设置项是假开关）；
     * 但也不能直接照搬"中心 ± 随机"——随机幅度一旦超过按钮边界就会点空。
     * E7 的按钮高度普遍 80px 以上，4px 偏移绝不会越界，因此既恢复人味又保证命中。
     *
     * 偏移量集中在 Humanizer.offsetPoint 里夹取并采用近高斯分布
     * （70% ±1px / 25% ±2px / 5% 满幅）——均匀分布本身是可被统计识别的特征。
     */
    fun tapExact(x: Float, y: Float) {
        val (px, py) = humanizer.offsetPoint(x, y)
        val path = Path().apply { moveTo(px, py) }
        val duration = humanizer.randInt(TAP_MIN_MS, TAP_MAX_MS).toLong()
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(),
            null, null
        )
    }

    /** Swipe with a clean straight line (jitter breaks scroll detection in games). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long) {
        val duration = baseMs + humanizer.randInt(-SWIPE_JITTER_MS, SWIPE_JITTER_MS)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(),
            null, null
        )
    }

    /**
     * 按速度倍率缩放后的等待。
     *
     * 下限 50ms：倍率调到 3x 时，原本 100ms 的等待会变成 33ms，
     * 过短的间隔会让连续手势被系统合并，因此设一个地板值。
     */
    fun sleepMs(ms: Long) {
        try {
            Thread.sleep((ms.toDouble() / speedMult()).toLong().coerceAtLeast(MIN_SLEEP_MS))
        } catch (e: InterruptedException) {
            // 被 stopBot 打断是正常控制流：恢复中断标志让上层循环退出
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "E7SA.Device"

        /** 截图等待上限（秒）。超时返回 null，由调用方 fail-closed。 */
        const val SCREENSHOT_TIMEOUT_SEC = 5L

        /** 调试帧写盘的最小间隔（ms）。 */
        const val DEBUG_SAVE_INTERVAL_MS = 8000L

        /** 调试帧文件名（App 私有目录）。 */
        const val DEBUG_FRAME_NAME = "debug_last.png"

        /** 点击手势时长区间（ms）——模拟真人按下的持续时间。 */
        const val TAP_MIN_MS = 30
        const val TAP_MAX_MS = 90

        /** 滑动手势时长的随机抖动（ms）。 */
        const val SWIPE_JITTER_MS = 60

        /** 等待下限（ms），防止高倍速把间隔压到系统会合并手势的程度。 */
        const val MIN_SLEEP_MS = 50L
    }
}
