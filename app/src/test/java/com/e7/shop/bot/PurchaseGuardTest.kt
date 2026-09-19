package com.e7.shop.bot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防误买判据单测（2026-09-18 新增）。
 *
 * priceMatches 是"购买弹窗三重验证"的最后一项，判据是**严格唯一**：
 * 画面里必须恰好有一条 6 位数字行等于期望价格。写成 >=1 就会在
 * "列表里同时出现两个价格"时误买；写成 contains 会在 1840000 里匹配到 184000。
 * 这两条都是不可逆的损失（买错 = 花掉真金白银），所以用测试钉死。
 */
class PurchaseGuardTest {

    private fun line(text: String, cy: Float = 100f, prob: Float = 0.9f) =
        PpOcr.OcrLine(text, 540f, cy, prob)

    private fun result(vararg texts: String) = DetectionResult(
        scene = Scene.BUY_DLG,
        lines = texts.mapIndexed { i, t -> line(t, cy = 100f + i * 60f) },
        candidates = emptyList(),
        yoloBoxes = emptyList(),
        engine = "test",
        diag = ""
    )

    private val bookmarkPrice = Tuning.BOOKMARK_PRICE.toString()   // 184000
    private val medalPrice = Tuning.MEDAL_PRICE.toString()         // 280000

    @Test
    fun matches_when_exactly_one_expected_price() {
        assertTrue(priceMatches(result("誓约书签", bookmarkPrice, "购买"), "bookmark"))
    }

    @Test
    fun rejects_when_price_appears_twice() {
        // 歧义 = 拒绝：无法确定买的是哪一格
        assertFalse(priceMatches(result(bookmarkPrice, bookmarkPrice), "bookmark"))
    }

    @Test
    fun rejects_when_price_is_a_different_item() {
        assertFalse(priceMatches(result(medalPrice), "bookmark"))
    }

    @Test
    fun rejects_when_no_six_digit_price_present() {
        assertFalse(priceMatches(result("誓约书签", "购买"), "bookmark"))
    }

    @Test
    fun rejects_seven_digit_number_containing_expected_price() {
        // 1840000 含 7 位数字 → 不参与匹配（长度必须恰好 6 位）
        assertFalse(priceMatches(result("1840000"), "bookmark"))
    }

    @Test
    fun matches_medal_price_for_medal_kind() {
        assertTrue(priceMatches(result("神秘奖牌", medalPrice), "medal"))
    }
}
