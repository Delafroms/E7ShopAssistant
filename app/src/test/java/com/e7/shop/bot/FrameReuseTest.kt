package com.e7.shop.bot

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 帧复用判定单测（2026-09-20 功耗优化）。
 *
 * 背景：AI 引擎的 OBSERVE 与 DECIDE_TARGETS 之间没有任何动作，却把**同一画面**
 * 识别了两遍（生产日志实测 2489 / 2529 帧，占全部完整识别的 40%）。现在允许复用，
 * 前提是"画面确实没变 ⇒ 识别结果必然相同"。
 *
 * 这个前提一旦判错（画面变了却判成没变），就会拿旧结果做决策 —— 那是漏买/误买的来源。
 * 所以本测试钉死两个方向：
 *  · **画面真没变** → 必须允许复用（否则优化失效，只是白花时间）；
 *  · **画面有任何可见变化** → 必须拒绝复用（宁可多识别一次，也不冒险）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameReuseTest {

    private val base = 0xFF203040.toInt()

    private fun frame(w: Int = 400, h: Int = 200, fill: Int = base) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(fill) }

    @Test
    fun identical_frames_are_reusable() {
        assertTrue("完全相同的两帧必须允许复用", frameNearlyIdentical(frame(), frame()))
    }

    @Test
    fun sub_threshold_noise_is_reusable() {
        // 截图噪声：极少数像素的轻微色差（通道差之和 < 阈值）不应阻止复用，
        // 否则真机上永远判"变了"，优化形同虚设。
        val a = frame()
        val b = frame()
        for (x in 0 until 8) b.setPixel(x, 0, 0xFF203045.toInt())
        assertTrue("轻微噪声不应阻止复用", frameNearlyIdentical(a, b))
    }

    @Test
    fun changed_region_is_not_reusable() {
        // 画面出现一块明显变化（模拟弹窗内容更新 / 渐显动画未结束）→ 必须重新识别
        val a = frame()
        val b = frame()
        for (x in 150 until 250) for (y in 80 until 120) b.setPixel(x, y, 0xFFFFFFFF.toInt())
        assertFalse("画面有明显变化时必须重新识别", frameNearlyIdentical(a, b))
    }

    @Test
    fun small_but_bright_change_is_not_reusable() {
        // 小面积但强对比的变化（模拟弹窗上一个数字/图标变化）——
        // 面积占比很低，但差异像素比例仍超过阈值，必须拒绝复用。
        val a = frame()
        val b = frame()
        for (x in 100 until 140) for (y in 40 until 60) b.setPixel(x, y, 0xFFFFFFFF.toInt())
        assertFalse("强对比的小面积变化也必须触发重新识别", frameNearlyIdentical(a, b))
    }

    @Test
    fun different_size_is_not_reusable() {
        assertFalse(
            "尺寸不同（分辨率切换 / 截图异常）一律不复用",
            frameNearlyIdentical(frame(400, 200), frame(200, 100))
        )
    }
}
