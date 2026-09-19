package com.e7.shop.bot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算闸门单测 —— 直接盯住**真钱**的边界语义。
 *
 * 为什么值得单独测：金币与天空石（付费货币）的闸门原先在两条管线里各写一遍，
 * 语义一旦被某一边改掉（例如把 >= 改成 >、或把 cap<=0 的解释换掉），
 * 后果是整夜超支 —— 而且不会崩、不会报错，只在账单上体现。
 * 现在判断收敛到 [BudgetGate]，用这些用例把语义钉死。
 */
class BudgetGateTest {

    /* ---------- 金币 ---------- */

    @Test
    fun gold_cap_zero_means_unlimited() {
        assertFalse("0 = 不限制（默认关闭该保护）", BudgetGate.goldExceeded(999_999_999L, 0L))
    }

    @Test
    fun gold_negative_cap_means_unlimited() {
        assertFalse(BudgetGate.goldExceeded(999_999_999L, -1L))
    }

    @Test
    fun gold_below_cap_keeps_running() {
        assertFalse(BudgetGate.goldExceeded(279_999L, 280_000L))
    }

    @Test
    fun gold_exactly_at_cap_stops() {
        assertTrue("正好花到上限即停（>= 语义）", BudgetGate.goldExceeded(280_000L, 280_000L))
    }

    @Test
    fun gold_over_cap_stops() {
        // 记账在点下之后（ACTION→COMMIT），所以"已花"最多超出上限一件商品 —— 这是刻意的保守方向
        assertTrue(BudgetGate.goldExceeded(560_000L, 280_000L))
    }

    /* ---------- 天空石 ---------- */

    @Test
    fun sky_cap_zero_means_unlimited() {
        assertFalse(BudgetGate.skyExceeded(99_999, 0))
    }

    @Test
    fun sky_below_cap_keeps_running() {
        assertFalse(BudgetGate.skyExceeded(297, 300))
    }

    @Test
    fun sky_exactly_at_cap_stops() {
        assertTrue("正好 300 颗就停（一次刷新 3 颗）", BudgetGate.skyExceeded(300, 300))
    }

    @Test
    fun sky_over_cap_stops() {
        assertTrue(BudgetGate.skyExceeded(303, 300))
    }

    /* ---------- 一致性 ---------- */

    @Test
    fun both_gates_agree_on_the_zero_boundary() {
        // 两个闸门对"0 = 不限制"的解释必须一致：不一致就会出现
        // "金币关了、天空石没关"这种看起来像 bug 的行为
        assertFalse(BudgetGate.goldExceeded(1L, 0L))
        assertFalse(BudgetGate.skyExceeded(1, 0))
    }
}
