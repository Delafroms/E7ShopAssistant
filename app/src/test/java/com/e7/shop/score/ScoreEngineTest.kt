package com.e7.shop.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装备评分引擎单测。
 *
 * 为什么值得测：评分表是**社区公认规则**，权重写错会让玩家按错误分数
 * 卖掉或留下装备 —— 这是不可逆的损失。而评分是纯函数，最适合单测锁住。
 */
class ScoreEngineTest {

    @Test
    fun speed_is_weighted_double() {
        val s = ScoreEngine.parseLine("速度 5")
        assertNotNull(s)
        assertEquals(10.0, s!!.points, 0.01)
    }

    @Test
    fun crit_rate_is_weighted_1_5() {
        val s = ScoreEngine.parseLine("暴击率 7%")
        assertNotNull(s)
        assertEquals(10.5, s!!.points, 0.01)
    }

    @Test
    fun crit_damage_is_weighted_1_1() {
        val s = ScoreEngine.parseLine("暴击伤害 8%")
        assertNotNull(s)
        assertEquals(8.8, s!!.points, 0.01)
    }

    @Test
    fun percent_attack_is_weighted_1_0() {
        val s = ScoreEngine.parseLine("攻击力 10%")
        assertNotNull(s)
        assertTrue(s!!.isPercent)
        assertEquals(10.0, s.points, 0.01)
    }

    @Test
    fun flat_attack_is_weighted_0_3() {
        val s = ScoreEngine.parseLine("攻击力 100")
        assertNotNull(s)
        assertEquals(30.0, s!!.points, 0.01)
    }

    @Test
    fun flat_hp_is_weighted_0_15() {
        val s = ScoreEngine.parseLine("生命力 1000")
        assertNotNull(s)
        assertEquals(150.0, s!!.points, 0.01)
    }

    @Test
    fun flat_defense_is_weighted_0_3() {
        val s = ScoreEngine.parseLine("防御力 100")
        assertNotNull(s)
        assertEquals(30.0, s!!.points, 0.01)
    }

    @Test
    fun effectiveness_is_always_percent() {
        val s = ScoreEngine.parseLine("效果命中 12")
        assertNotNull(s)
        assertTrue("效果命中即使没写 % 也按百分比计", s!!.isPercent)
        assertEquals(12.0, s.points, 0.01)
    }

    @Test
    fun speed_is_never_percent() {
        val s = ScoreEngine.parseLine("速度 5%")
        assertNotNull(s)
        assertTrue("速度是固定值，不是百分比", !s!!.isPercent)
    }

    @Test
    fun crit_damage_is_not_swallowed_by_crit_rate() {
        // 匹配顺序错了会把"暴击伤害"当成"暴击率"——权重 1.5 vs 1.1，分数会错
        val s = ScoreEngine.parseLine("暴击伤害 10%")
        assertEquals("Crit Damage", s!!.name)
    }

    @Test
    fun english_stat_names_are_supported() {
        assertNotNull(ScoreEngine.parseLine("Speed 5"))
        assertNotNull(ScoreEngine.parseLine("Crit Rate 7%"))
        assertNotNull(ScoreEngine.parseLine("Attack 10%"))
    }

    @Test
    fun unrecognized_line_returns_null() {
        assertNull(ScoreEngine.parseLine("这是一行无关文字"))
        assertNull(ScoreEngine.parseLine(""))
    }

    @Test
    fun stat_name_without_number_returns_null() {
        assertNull("只有名字没有数值，无法计分", ScoreEngine.parseLine("速度"))
    }

    @Test
    fun score_sums_all_lines() {
        val r = ScoreEngine.score(listOf("速度 5", "暴击率 7%"))
        assertEquals(2, r.lines.size)
        assertEquals(20.5, r.total, 0.01)
    }

    @Test
    fun score_deduplicates_same_stat() {
        // OCR 可能把同一行读两次；重复计分会虚高
        val r = ScoreEngine.score(listOf("速度 5", "速度 5"))
        assertEquals(1, r.lines.size)
        assertEquals(10.0, r.total, 0.01)
    }

    @Test
    fun score_sorts_by_points_descending() {
        // 速度 5 = 10 分；攻击力 100（固定值）= 30 分 → 攻击力应排在前
        val r = ScoreEngine.score(listOf("速度 5", "攻击力 100"))
        assertEquals("Attack (flat)", r.lines[0].name)
        assertEquals("Speed", r.lines[1].name)
    }

    @Test
    fun speed_outranks_equal_value_percent_attack() {
        // 同为 10：速度 ×2.0 = 20 分，攻击力% ×1.0 = 10 分 → 速度在前
        val r = ScoreEngine.score(listOf("攻击力 10%", "速度 10"))
        assertEquals("Speed", r.lines[0].name)
    }

    @Test
    fun score_ignores_unparseable_lines() {
        val r = ScoreEngine.score(listOf("速度 5", "无关文字", ""))
        assertEquals(1, r.lines.size)
    }

    @Test
    fun decimal_values_are_supported() {
        val s = ScoreEngine.parseLine("速度 4.5")
        assertEquals(9.0, s!!.points, 0.01)
    }

    @Test
    fun case_insensitive_match_does_not_shift_the_index() {
        // 回归测试（2026-09-19）：旧版在 lowercase() 后的串上取下标、却拿它去切**原串**。
        // 'İ'（U+0130）小写化后是两个 code unit，索引整体右移一位 →
        // 尾巴少切一位数字，分数**静默**算错（123 被读成 23），而且不会抛任何异常。
        val s = ScoreEngine.parseLine("İ Speed123")
        assertNotNull("带 İ 的行仍应被识别", s)
        assertEquals("数值不能被索引偏移吃掉一位", 123.0, s!!.value, 0.01)
        assertEquals(246.0, s.points, 0.01)
    }
}
