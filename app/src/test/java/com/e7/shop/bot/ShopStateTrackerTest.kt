package com.e7.shop.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观测层（ShopStateTracker）单测 —— 纯逻辑，JVM 可跑，无需设备。
 *
 * 为什么这个类值得测：它是**群友报障时唯一的证据来源**。玩家说"买完不下滑 /
 * 卡住不动 / 反复点同一行"时，全靠这里输出的状态变迁去区分
 * 「识别没看见」与「看见了但决策没动」。一旦它把"行位置抖动"误报成
 * "旧行消失 + 新行出现"，日志就会指向完全错误的方向 —— 比没有日志更糟。
 *
 * 重点覆盖三处容易写错、且写错就会误导排查的地方：
 *  · 容差判定（行位置抖动 ≠ 换行）
 *  · MIN_ROW_TOL 下限（低分辨率下 tol 很小，不设下限会把抖动读成换行）
 *  · 候选顺序无关性（识别层输出顺序不保证稳定，不能因此判成"变了"）
 */
class ShopStateTrackerTest {

    private fun cand(kind: String, cy: Float, tol: Float = 60f) =
        Candidate(kind, 1000f, cy, 0.9f, tol, listOf("test"))

    private fun line(text: String, cy: Float) = PpOcr.OcrLine(text, 1000f, cy, 0.9f)

    private fun result(
        scene: Scene = Scene.SHOP_LIST,
        cands: List<Candidate> = emptyList(),
        lines: List<PpOcr.OcrLine> = emptyList()
    ) = DetectionResult(scene, lines, cands, emptyList(), "test", "")

    /* ================= 基本事件语义 ================= */

    @Test
    fun first_observation_reports_first_and_keeps_streak_zero() {
        val t = ShopStateTracker()
        val s = t.observe(result(cands = listOf(cand("bookmark", 500f))))
        assertTrue("首帧必须是 first，实际: $s", s.startsWith("type=first"))
        assertEquals("首帧不应计入 staticStreak", 0, t.staticStreak)
        assertEquals(1, t.rounds)
    }

    @Test
    fun identical_frames_report_same_and_increment_streak() {
        val t = ShopStateTracker()
        val r = result(cands = listOf(cand("bookmark", 500f)))
        t.observe(r)
        val s2 = t.observe(r)
        val s3 = t.observe(r)
        assertTrue("第 2 帧应为 same，实际: $s2", s2.startsWith("type=same"))
        assertTrue("第 3 帧应为 same，实际: $s3", s3.startsWith("type=same"))
        assertEquals(2, t.staticStreak)
        assertEquals(3, t.rounds)
    }

    @Test
    fun changed_content_resets_static_streak() {
        val t = ShopStateTracker()
        t.observe(result(cands = listOf(cand("bookmark", 500f))))
        t.observe(result(cands = listOf(cand("bookmark", 500f))))
        assertEquals(1, t.staticStreak)

        val s = t.observe(result(cands = listOf(cand("medal", 500f))))
        assertTrue("内容变化应为 changed，实际: $s", s.startsWith("type=changed"))
        assertEquals("变化后 staticStreak 必须归零", 0, t.staticStreak)
    }

    /* ================= 容差判定（最容易误导排查的地方） ================= */

    @Test
    fun row_jitter_within_tolerance_is_not_reported_as_gone_and_added() {
        val t = ShopStateTracker()
        t.observe(result(cands = listOf(cand("bookmark", 500f, tol = 60f))))
        // 同一行上移 20px（< tol=60），另加一行文本让指纹变化以触发 changed 分支
        val s = t.observe(
            result(
                cands = listOf(cand("bookmark", 520f, tol = 60f)),
                lines = listOf(line("金币", 520f))
            )
        )
        assertTrue("应为 changed，实际: $s", s.startsWith("type=changed"))
        assertFalse("行位置抖动被误报成消失: $s", s.contains("gone="))
        assertFalse("行位置抖动被误报成新增: $s", s.contains("added="))
        assertTrue(
            "候选未变而指纹变了时应给出显式说明，实际: $s",
            s.contains("rows-identical-but-fingerprint-changed")
        )
    }

    @Test
    fun row_moved_beyond_tolerance_is_reported_as_gone_and_added() {
        val t = ShopStateTracker()
        t.observe(result(cands = listOf(cand("bookmark", 500f, tol = 60f))))
        val s = t.observe(
            result(
                cands = listOf(cand("bookmark", 900f, tol = 60f)),
                lines = listOf(line("金币", 900f))
            )
        )
        assertTrue("超出容差应报消失: $s", s.contains("gone="))
        assertTrue("超出容差应报新增: $s", s.contains("added="))
    }

    /**
     * MIN_ROW_TOL 下限的守卫测试。
     *
     * 低分辨率下识别层推导出的 tol 可能只有几个像素。若不设下限，
     * 一次正常的行位置抖动（30px）就会被读成"旧行消失 + 新行出现"，
     * 日志随即失去诊断价值 —— 这正是该下限存在的理由。
     */
    @Test
    fun tiny_tolerance_still_protected_by_min_row_tol_floor() {
        val t = ShopStateTracker()
        t.observe(result(cands = listOf(cand("bookmark", 500f, tol = 5f))))
        val s = t.observe(
            result(
                cands = listOf(cand("bookmark", 530f, tol = 5f)), // 差 30px，小于 MIN_ROW_TOL=40
                lines = listOf(line("金币", 530f))
            )
        )
        assertFalse("容差下限失效，抖动被误报成消失: $s", s.contains("gone="))
        assertFalse("容差下限失效，抖动被误报成新增: $s", s.contains("added="))
    }

    /* ================= 售罄状态翻转 ================= */

    @Test
    fun sold_out_flip_is_reported_as_flip() {
        val t = ShopStateTracker()
        t.observe(result(cands = listOf(cand("bookmark", 500f, tol = 60f))))
        val s = t.observe(
            result(
                cands = listOf(cand("bookmark", 500f, tol = 60f)),
                lines = listOf(line("售罄", 500f)) // 同一行变为售空
            )
        )
        assertTrue("售罄翻转未被报告: $s", s.contains("flip="))
        assertTrue("翻转内容应含 sold 标记: $s", s.contains("sold"))
    }

    @Test
    fun sold_out_line_far_away_does_not_mark_row_sold_out() {
        val t = ShopStateTracker()
        // 售罄文本距离该行 400px（远超 tol=60）→ 不应影响本行状态
        val s = t.observe(
            result(
                cands = listOf(cand("bookmark", 500f, tol = 60f)),
                lines = listOf(line("售罄", 900f))
            )
        )
        assertTrue("远处售罄文本不应污染本行: $s", s.contains("bookmark@500:ok"))
    }

    /* ================= 顺序无关性 ================= */

    /**
     * 识别层输出的候选顺序不保证稳定（YOLO 框序 / 传统引擎行序都可能变），
     * 因此"顺序不同"绝不能被判成"内容变了"，否则 staticStreak 会被反复打断。
     */
    @Test
    fun candidate_order_does_not_affect_static_detection() {
        val t = ShopStateTracker()
        t.observe(result(cands = listOf(cand("bookmark", 500f), cand("medal", 900f))))
        val s = t.observe(result(cands = listOf(cand("medal", 900f), cand("bookmark", 500f))))
        assertTrue("候选顺序不同被误判为内容变化: $s", s.startsWith("type=same"))
    }

    /* ================= 会话重置 ================= */

    @Test
    fun reset_clears_all_state_and_restarts_from_first() {
        val t = ShopStateTracker()
        val r = result(cands = listOf(cand("bookmark", 500f)))
        t.observe(r)
        t.observe(r)
        assertEquals(1, t.staticStreak)

        t.reset()
        assertEquals(0, t.staticStreak)
        assertEquals(0, t.rounds)

        val s = t.observe(r)
        assertTrue("reset 后必须重新从 first 开始: $s", s.startsWith("type=first"))
    }

    /* ================= 空场景 ================= */

    @Test
    fun empty_candidates_are_handled_without_crash() {
        val t = ShopStateTracker()
        val s1 = t.observe(result(scene = Scene.OTHER))
        val s2 = t.observe(result(scene = Scene.OTHER))
        assertTrue(s1.startsWith("type=first"))
        assertTrue("空候选连续两帧应为 same: $s2", s2.startsWith("type=same"))
        assertTrue(s1.contains("rows=0"))
    }
}
