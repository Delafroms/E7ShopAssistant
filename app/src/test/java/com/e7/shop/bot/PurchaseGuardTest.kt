package com.e7.shop.bot

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 防误买判据单测。
 *
 * **2026-09-20 语义修正 —— 依据是真机生产日志，不是推测。**
 *
 * 旧判据是「全帧恰好一条 6 位数字等于期望价格」，并且用两条测试把它钉死。
 * 但 2026-09-20 那一夜的日志证明它在真机上**必然失败**：
 *  · 购买弹窗盖在商店列表上，玩家要点的那一行仍在弹窗下方可见；
 *  · 于是整帧里 184000 出现两次（弹窗内一次 + 列表行一次）；
 *  · priceMatches 100% 返回 false → 2472 次「买 → 取消 → 再买」活锁、
 *    只买到 8 件、4.4 小时满速空转（手机严重发烫）。
 *
 * 而且旧判据的语义是**反的**：
 *  · 「两个 184000」= 同一件商品的两处显示 → 不是歧义，却被拒绝（→ 活锁）；
 *  · 「184000 与 280000 同屏」= 真的可能买错 → 却被放行（旧实现只把**等于期望价**的
 *    计入候选，280000 根本不进统计，"唯一一条"照样成立）。
 *
 * 现在：① 判据只看**弹窗内容区**（由 [dialogLines] 提供，见 Recognition 的说明）；
 * ② 弹窗内容区内出现期望价格即通过 —— 重复同价不是歧义，真歧义由
 * [dialogConfirmed] 的 textOk（弹窗内必须读到目标商品名）挡住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurchaseGuardTest {

    private fun line(text: String, cy: Float = 100f, prob: Float = 0.9f) =
        PpOcr.OcrLine(text, 540f, cy, prob)

    private fun lines(vararg texts: String) =
        texts.mapIndexed { i, t -> line(t, cy = 100f + i * 60f) }

    private val bookmarkPrice = Tuning.BOOKMARK_PRICE.toString()   // 184000
    private val medalPrice = Tuning.MEDAL_PRICE.toString()         // 280000

    @Test
    fun matches_when_expected_price_present() {
        assertTrue(priceMatches(lines("誓约书签", bookmarkPrice, "购买"), "bookmark"))
    }

    @Test
    fun matches_when_same_price_appears_twice() {
        // 2026-09-20 修正：同一价格的重复**不是**歧义 —— 两处 184000 都指向誓约书签
        // （弹窗内一处 + 弹窗下方列表行一处，真机日志里就是这么出现的）。
        // 旧版在这里断言 false，那条断言正是 2472 次活锁的判据来源。
        assertTrue(priceMatches(lines(bookmarkPrice, bookmarkPrice), "bookmark"))
    }

    @Test
    fun rejects_when_price_is_a_different_item() {
        assertFalse(priceMatches(lines(medalPrice), "bookmark"))
    }

    @Test
    fun rejects_when_no_six_digit_price_present() {
        assertFalse(priceMatches(lines("誓约书签", "购买"), "bookmark"))
    }

    @Test
    fun rejects_seven_digit_number_containing_expected_price() {
        // 1840000 含 7 位数字 → 不参与匹配（长度必须恰好 6 位）
        assertFalse(priceMatches(lines("1840000"), "bookmark"))
    }

    @Test
    fun matches_medal_price_for_medal_kind() {
        assertTrue(priceMatches(lines("神秘奖牌", medalPrice), "medal"))
    }

    /* ---- 弹窗内容区（2026-09-20 新增）：弹窗外的列表行不得参与验证 ---- */

    private val imgH = 1272

    private fun dialogResult(vararg texts: String): DetectionResult {
        // 真机几何（2800×1272 生产日志）：弹窗按钮 y≈913、弹窗内商品名/图标/价格 y≈623、
        // 弹窗外列表行 y≈1130。
        val lines = texts.map { t ->
            line(t, cy = if (t == "取消") 913f else 623f)
        }.toMutableList()
        // 弹窗下方仍可见的列表行：同一件书签（这正是真机上污染判据的那一行）
        lines.add(line("誓约书签", cy = 1130f))
        lines.add(line("184000", cy = 1130f))
        return DetectionResult(
            scene = Scene.BUY_DLG,
            lines = lines,
            candidates = emptyList(),
            yoloBoxes = listOf(YoloDet.Box(0, 1221f, 623f, 60f, 60f, 0.97f)),
            engine = "test",
            diag = ""
        )
    }

    @Test
    fun dialogLines_excludes_list_row_outside_dialog_window() {
        val r = dialogResult("誓约书签", "184000", "取消")
        val w = dialogLines(r, imgH)
        assertTrue("弹窗锚点存在时必须能界定内容区", w != null)
        assertTrue("弹窗内的价格必须保留", w!!.any { it.cy == 623f })
        assertFalse(
            "弹窗外（y≈1130）的列表行必须被排除 —— 它就是 2026-09-20 活锁的污染源",
            w.any { it.cy == 1130f }
        )
    }

    @Test
    fun dialogConfirmed_passes_when_list_row_below_shows_same_price() {
        // 这是 2026-09-20 活锁的**核心回归用例**：弹窗内是誓约书签，弹窗下方列表里
        // 同一件书签仍可见（整帧出现两个 184000）。旧判据在此返回 false → 取消 → 再买。
        val r = dialogResult("誓约书签", "184000", "取消")
        assertTrue(
            "弹窗内商品名+图标+价格都对，弹窗外的同价列表行不得让验证失败",
            dialogConfirmed(r, Bitmap.createBitmap(2800, imgH, Bitmap.Config.ARGB_8888), "bookmark")
        )
    }

    @Test
    fun dialogConfirmed_fails_when_anchor_missing() {
        // 锚点读不到（既没有按钮框、也没有「取消」文本）→ fail-closed：
        // 绝不退回整帧扫描 —— 那会把弹窗外的列表行当成弹窗证据而误买。
        val r = DetectionResult(
            scene = Scene.BUY_DLG,
            lines = listOf(line("誓约书签", cy = 623f), line("184000", cy = 623f)),
            candidates = emptyList(),
            yoloBoxes = listOf(YoloDet.Box(0, 1221f, 623f, 60f, 60f, 0.97f)),
            engine = "test",
            diag = ""
        )
        assertFalse(
            "无法界定弹窗内容区时必须判为验证不通过（fail-closed）",
            dialogConfirmed(r, Bitmap.createBitmap(2800, imgH, Bitmap.Config.ARGB_8888), "bookmark")
        )
    }
}
