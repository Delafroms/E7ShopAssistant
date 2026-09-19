package com.e7.shop.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时序常量**金值测试**（golden values）—— 2026-09-19 把两个引擎里 25 处裸字面量
 * 收敛进 [Tuning] 时补的安全网。
 *
 * 为什么需要：那次重构的口号是"只改名、不改值"。但"值有没有被手滑改掉"光靠 code review
 * 看不出来 —— 而等待时长直接决定"慢设备上会不会误判超时 / 快设备上会不会漏买"。
 * 这组断言把每个常量的**确切数值**钉住：任何无意的改动都会立刻变红，
 * 有意调整时则必须同时改这里（等于强制留痕）。
 */
class TuningTimingTest {

    @Test
    fun poll_and_retry_waits_are_unchanged() {
        assertEquals(500L, Tuning.POLL_PAUSED_MS)
        assertEquals(1500L, Tuning.NET_RETRY_WAIT_MS)
        assertEquals(450L, Tuning.POLL_TICK_MS)
        assertEquals(600L, Tuning.DIALOG_RECHECK_MS)
        assertEquals(800L, Tuning.RESHOT_RETRY_MS)
        assertEquals(800L, Tuning.RESCAN_SETTLE_MS)
        assertEquals(700L, Tuning.SETTLE_AFTER_RECYCLE_MS)
    }

    @Test
    fun jitter_ranges_are_unchanged() {
        assertEquals(220, Tuning.PRE_TAP_JITTER_MIN_MS)
        assertEquals(400, Tuning.PRE_TAP_JITTER_MAX_MS)
        assertEquals(350, Tuning.DIALOG_POLL_MIN_MS)
        assertEquals(600, Tuning.DIALOG_POLL_MAX_MS)
        assertEquals(220, Tuning.DIALOG_SETTLE_MIN_MS)
        assertEquals(360, Tuning.DIALOG_SETTLE_MAX_MS)
        assertEquals(400, Tuning.REFRESH_POLL_MIN_MS)
        assertEquals(700, Tuning.REFRESH_POLL_MAX_MS)
        assertEquals(200, Tuning.SHOP_LOAD_POLL_MIN_MS)
        assertEquals(350, Tuning.SHOP_LOAD_POLL_MAX_MS)
        assertEquals(350, Tuning.SLOT6_POLL_MIN_MS)
        assertEquals(550, Tuning.SLOT6_POLL_MAX_MS)
        assertEquals(250, Tuning.RECHECK_SETTLE_MIN_MS)
        assertEquals(450, Tuning.RECHECK_SETTLE_MAX_MS)
        assertEquals(200, Tuning.POLL_TIGHT_MIN_MS)
        assertEquals(380, Tuning.POLL_TIGHT_MAX_MS)
    }

    @Test
    fun every_jitter_range_is_ordered_and_positive() {
        // 反过来的区间会让 randInt 抛异常（或退化成固定值）—— 这类笔误不该靠真机发现
        val ranges = listOf(
            "PRE_TAP" to (Tuning.PRE_TAP_JITTER_MIN_MS to Tuning.PRE_TAP_JITTER_MAX_MS),
            "DIALOG_POLL" to (Tuning.DIALOG_POLL_MIN_MS to Tuning.DIALOG_POLL_MAX_MS),
            "DIALOG_SETTLE" to (Tuning.DIALOG_SETTLE_MIN_MS to Tuning.DIALOG_SETTLE_MAX_MS),
            "REFRESH_POLL" to (Tuning.REFRESH_POLL_MIN_MS to Tuning.REFRESH_POLL_MAX_MS),
            "SHOP_LOAD_POLL" to (Tuning.SHOP_LOAD_POLL_MIN_MS to Tuning.SHOP_LOAD_POLL_MAX_MS),
            "SLOT6_POLL" to (Tuning.SLOT6_POLL_MIN_MS to Tuning.SLOT6_POLL_MAX_MS),
            "RECHECK_SETTLE" to (Tuning.RECHECK_SETTLE_MIN_MS to Tuning.RECHECK_SETTLE_MAX_MS),
            "POLL_TIGHT" to (Tuning.POLL_TIGHT_MIN_MS to Tuning.POLL_TIGHT_MAX_MS)
        )
        for ((name, r) in ranges) {
            assertTrue(name + " 的随机区间反了", r.first < r.second)
            assertTrue(name + " 的下限必须为正", r.first > 0)
        }
    }
}
