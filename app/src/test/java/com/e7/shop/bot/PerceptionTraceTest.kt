package com.e7.shop.bot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧级感知追踪的格式测试。
 *
 * 为什么值得测：这段文本是漏买复盘时**唯一的现场记录** —— 日志是事后才看的，
 * 等发现问题时格式已经写进历史文件、无法补救。字段缺失或分隔符漂移会让
 * 解析脚本和历史日志全部失效，所以把格式用单测钉死。
 */
class PerceptionTraceTest {

    private fun line(t: String, cx: Float = 0f, cy: Float = 0f, p: Float = 0.9f) =
        PpOcr.OcrLine(t, cx, cy, p)

    private fun box(cls: Int, cx: Float, cy: Float, prob: Float) =
        YoloDet.Box(cls, cx, cy, 40f, 40f, prob)

    private fun result(
        scene: Scene = Scene.SHOP_LIST,
        lines: List<PpOcr.OcrLine> = emptyList(),
        cands: List<Candidate> = emptyList(),
        boxes: List<YoloDet.Box> = emptyList()
    ) = DetectionResult(scene, lines, cands, boxes, "yolo", "diag")

    @Test
    fun emptyFrame_marks_every_section_with_dash() {
        val s = formatPerceptionTrace("FRAME", result())
        assertTrue("场景必须记录", s.contains("scene=SHOP_LIST"))
        assertTrue("引擎必须记录", s.contains("eng=yolo"))
        assertTrue("空 OCR 要显式标 -（不能省略，否则无法区分空与未记录）", s.contains("ocr(0): -"))
        assertTrue(s.contains("yolo(0): -"))
        assertTrue(s.contains("cand(0): -"))
    }

    @Test
    fun ocrTexts_areJoinedWithSeparator() {
        val s = formatPerceptionTrace(
            "FRAME",
            result(lines = listOf(line("神秘奖牌"), line("购买"), line("280000")))
        )
        assertTrue(s.contains("ocr(3): 神秘奖牌│购买│280000"))
    }

    @Test
    fun longOcr_isTruncated_withEllipsis() {
        val long = (1..40).joinToString("") { "很长的商品名称$it" }
        val s = formatPerceptionTrace("FRAME", result(lines = listOf(line(long))), maxOcrChars = 20)
        assertTrue("超长 OCR 必须截断并留标记，否则单帧日志会失控", s.contains("…"))
    }

    @Test
    fun yoloBoxes_carryClassAndPosition() {
        val s = formatPerceptionTrace("FRAME", result(boxes = listOf(box(1, 495f, 1200f, 0.42f))))
        assertTrue(s.contains("yolo(1):"))
        assertTrue("类别名与坐标是判断「模型看到但没进候选」的依据", s.contains("medal(495,1200,0.42)"))
    }

    @Test
    fun floatFormat_isLocaleIndependent() {
        // 某些区域设置下 "%.2f" 会输出逗号，解析脚本会因此失效
        val s = formatPerceptionTrace("FRAME", result(boxes = listOf(box(0, 1f, 2f, 0.5f))))
        assertTrue(s.contains("0.50"))
        assertFalse(s.contains("0,50"))
    }

    @Test
    fun candidates_showKindRowToleranceAndEvidence() {
        val c = Candidate("medal", 100f, 1200f, 0.42f, 117f, listOf("yolo", "ocr"))
        val s = formatPerceptionTrace("FRAME", result(cands = listOf(c)))
        assertTrue(s.contains("cand(1): medal@1200[tol117][yolo+ocr]"))
    }

    @Test
    fun stage_isRecorded_forPostMortemAlignment() {
        val s = formatPerceptionTrace("REFRESH_VERIFY", result())
        assertTrue("阶段名用于和 State 日志按时间对齐", s.startsWith("stage=REFRESH_VERIFY"))
    }
}
