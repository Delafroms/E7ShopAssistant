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


    /**
     * 截取当前屏幕。
     *
     * 失败返回 null —— 调用方必须按 fail-closed 处理（不买、不刷新）。
     * 同时通过 [onShotResult] 上报健康度，供悬浮窗显示"截图正常/失败"。
     */
    fun screenshot(): Bitmap? {
        val t0 = System.currentTimeMillis()
        val bmp = if (Build.VERSION.SDK_INT >= 30) takeScreenshotInternal() else null
        val t1 = System.currentTimeMillis()
        onShotResult(bmp != null)
        // 2026-09-22 删除「调试帧 PNG 压缩」（原为 ≥8 秒一张）：
        // C1 实测它占墙上时间 6.7%、每帧约 308ms —— 而每帧总成本约 1016ms，
        // 也就是说 30% 的取帧时间花在一个纯排查用的副产品上。关掉零功能影响。
        // 需要逐帧画面时改用 DiagnosticsRunner 采集或 adb screencap，不再挂在主循环上。
        //
        // 分项计时（2026-09-21，为查"CPU 130 分钟去向"）：
        //  · grab = 截图全流程（含 GPU→CPU 回读 14.2MB 的 copy）
        Profiler.record("grab", t1 - t0)
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
        try {
            if (!latch.await(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                Log.w(TAG, "takeScreenshot timed out after $SCREENSHOT_TIMEOUT_SEC s")
            }
        } catch (e: InterruptedException) {
            // 停止时的**正常**中断（2026-09-19 修复）。
            // 旧版不吞中断：InterruptedException 会一路冒泡到引擎的 catch (Exception)，
            // 被记成 "E7SA.Crash engine aborted: InterruptedException" + err=异常
            //（实测日志 01:33:33）—— 把玩家手动点停止记成了崩溃。
            // 这里恢复中断标志并 fail-closed 返回 null，让上层循环靠 stopRequested() 干净退出。
            Thread.currentThread().interrupt()
            Log.i(TAG, "takeScreenshot interrupted (stop requested)")
            return null
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
            val t = (ms.toDouble() / speedMult()).toLong().coerceAtLeast(MIN_SLEEP_MS)
            // C1（2026-09-22）：把「等待」也计入窗口。实测每帧成本降了 33% 而吞吐没涨，
            // 说明瓶颈在等待而不是计算 —— 不让等待可见就永远在优化错的东西。
            Profiler.record("sleep", t)
            Thread.sleep(t)
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
