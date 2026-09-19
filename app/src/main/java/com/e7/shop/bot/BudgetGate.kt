package com.e7.shop.bot

/**
 * 预算闸门（金币 / 天空石）—— 两条点击管线**共用同一份判断**。
 *
 * 为什么抽出来（2026-09-19）：
 *  · 同一条规则原先在 [BotEngine]（doBuy / doRefresh）与 [AiBotEngine]（budgetBlocked）
 *    各写一遍、共 3 处。这个项目的系统性坑就是"双管线同步陷阱"——改一条漏另一条，
 *    而这里管的是**真钱**（金币 + 付费货币天空石），漏一次就是整夜白烧。
 *  · 判断本身是纯函数，最适合用单测把边界语义钉死（见 BudgetGateTest）。
 *
 * 语义（写死在这里，两条管线不允许各自解释）：
 *  · 上限 <= 0 表示**不限制**（默认关闭该保护）；
 *  · 判据是「**已花 ≥ 上限** 就停」→ 最多只会**超出上限一件商品**。这是刻意的保守方向：
 *    记账发生在点下之后（ACTION→COMMIT），宁可多买一件，也不要因为提前停手而漏买；
 *  · 两个闸门互相独立，金币先判（"金币不足"的提示更常见、更好懂）。
 */
object BudgetGate {

    /** 金币是否已达上限（cap <= 0 = 不限制）。 */
    fun goldExceeded(spent: Long, cap: Long): Boolean = cap > 0L && spent >= cap

    /** 天空石是否已达上限（cap <= 0 = 不限制）。 */
    fun skyExceeded(spent: Int, cap: Int): Boolean = cap > 0 && spent >= cap
}
