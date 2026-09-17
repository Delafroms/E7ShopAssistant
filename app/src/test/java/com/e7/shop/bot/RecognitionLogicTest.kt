package com.e7.shop.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别层纯逻辑单测（JVM，无需设备）。
 *
 * 为什么这些函数值得测：它们直接决定"点哪里"和"买不买"。
 * 一个符号写反就是点错行或漏买，而真机复现一次要跑完整轮挂机。
 * 这些函数不依赖 Android 框架（只用数据类），因此可以在 JVM 上毫秒级验证。
 *
 * 重点覆盖的是**踩过坑的地方**：
 *  · 行容差必须由行距推导（跨行点错的根因）
 *  · 场景判定不能把刷新弹窗误判成购买弹窗（会误买）
 *  · 购买键定位必须 fail-closed（够不着就不点）
 */
class RecognitionLogicTest {

    private fun line(text: String, cx: Float, cy: Float, prob: Float = 0.9f) =
        PpOcr.OcrLine(text, cx, cy, prob)

    /* ================= 关键词匹配 ================= */

    @Test
    fun norm_strips_spaces_and_lowercases() {
        assertEquals("buynow", norm("Buy Now"))
        assertEquals("购买", norm(" 购 买 "))
    }

    @Test
    fun hasAny_matches_any_keyword() {
        assertTrue(hasAny("立即更新", REFRESH_BTN_KW))
        assertTrue(hasAny("Refresh", REFRESH_BTN_KW))
        assertFalse(hasAny("确定", REFRESH_BTN_KW))
    }

    @Test
    fun itemKind_distinguishes_bookmark_and_medal() {
        assertEquals("bookmark", itemKind("誓约书签"))
        assertEquals("medal", itemKind("神秘奖牌"))
        assertNull(itemKind("立即更新"))
    }

    @Test
    fun itemKind_supports_traditional_chinese() {
        assertEquals("bookmark", itemKind("誓約書籤"))
        assertEquals("medal", itemKind("神秘獎牌"))
    }

    /* ================= 场景判定 ================= */

    @Test
    fun refresh_dialog_is_not_mistaken_for_buy_dialog() {
        // 刷新弹窗同时含"更新/天空石"与"确认"——顺序错了就会被当成购买弹窗而误买
        val lines = listOf(
            line("立即更新", 1400f, 600f),
            line("更新需要消耗3个天空石", 1400f, 700f),
            line("确认", 1600f, 900f),
            line("取消", 1200f, 900f)
        )
        assertEquals(Scene.REFRESH_DLG, sceneOf(lines))
    }

    @Test
    fun buy_dialog_is_detected() {
        val lines = listOf(
            line("购买商品", 1400f, 500f),
            line("誓约书签", 1400f, 600f),
            line("184000", 1400f, 700f),
            line("购买", 1600f, 900f),
            line("取消", 1200f, 900f)
        )
        assertEquals(Scene.BUY_DLG, sceneOf(lines))
    }

    @Test
    fun shop_list_is_detected() {
        val lines = listOf(
            line("立即更新", 1400f, 1100f),
            line("购买", 1600f, 400f),
            line("购买", 1600f, 660f)
        )
        assertEquals(Scene.SHOP_LIST, sceneOf(lines))
    }

    @Test
    fun unrelated_screen_is_other() {
        assertEquals(Scene.OTHER, sceneOf(listOf(line("主界面", 100f, 100f))))
    }

    /* ================= 行距与容差（跨行点错的根因） ================= */

    @Test
    fun row_pitch_uses_minimum_gap_not_median() {
        // 四行购买键，其中一行被 OCR 漏读 → 间距出现 260 与 520
        val imgH = 1272
        val lines = listOf(
            line("购买", 1600f, 200f),
            line("购买", 1600f, 460f),
            // 720 这一行漏读
            line("购买", 1600f, 980f)
        )
        val pitch = rowPitch(lines, imgH)
        // 最小值 260 才是真实行距；若取中位数会得到 520，容差随之翻倍并跨行
        assertTrue("行距应贴近 260 而不是 520，实际=" + pitch, pitch < 300f)
    }

    @Test
    fun row_tolerance_is_always_below_half_pitch() {
        val imgH = 1272
        val lines = (0 until 5).map { line("购买", 1600f, 200f + it * 260f) }
        val pitch = rowPitch(lines, imgH)
        val tol = rowTol(lines, imgH)
        assertTrue("容差必须小于行距一半，否则相邻行互相污染", tol < pitch * 0.5f)
    }

    @Test
    fun row_pitch_is_clamped_to_sane_range() {
        val imgH = 1272
        // 极端：只有一个购买键 → 无间距可用，走兜底值
        val single = listOf(line("购买", 1600f, 500f))
        val pitch = rowPitch(single, imgH)
        assertTrue(pitch >= imgH * Tuning.ROW_PITCH_MIN)
        assertTrue(pitch <= imgH * Tuning.ROW_PITCH_MAX)
    }

    @Test
    fun row_tolerance_scales_down_on_low_resolution() {
        // 720p 横屏：行距约 100px。容差必须随之缩小，不能写死像素
        val imgH = 720
        val lines = (0 until 4).map { line("购买", 900f, 100f + it * 100f) }
        val tol = rowTol(lines, imgH)
        assertTrue("720p 下容差应远小于 90px（旧版写死值）", tol < 90f)
    }

    /* ================= 购买键定位（fail-closed） ================= */

    @Test
    fun rowBuyPoint_only_accepts_key_inside_row_tolerance() {
        val lines = listOf(
            line("购买", 1600f, 200f),
            line("购买", 1600f, 460f)
        )
        val pt = rowBuyPoint(lines, cy = 460f, tol = 100f, cx = 1000f)
        assertNotNull(pt)
        assertEquals(460f, pt!!.second, 0.01f)
    }

    @Test
    fun rowBuyPoint_returns_null_instead_of_clicking_neighbour_row() {
        val lines = listOf(line("购买", 1600f, 200f))
        // 目标行在 900，唯一的购买键在 200，距离 700 远超容差 100
        val pt = rowBuyPoint(lines, cy = 900f, tol = 100f, cx = 1000f)
        assertNull("宁可漏买也不能跨行点错", pt)
    }

    @Test
    fun rowBuyPoint_ignores_dialog_title_containing_buy_word() {
        val lines = listOf(
            line("购买商品", 1600f, 200f),   // 标题，不是按钮
            line("购买", 1600f, 460f)
        )
        val pt = rowBuyPoint(lines, cy = 200f, tol = 400f, cx = 1000f)
        // 标题被排除，最近的按钮在 460
        assertEquals(460f, pt!!.second, 0.01f)
    }

    @Test
    fun rowBuyPoint_only_accepts_keys_right_of_target_column() {
        val lines = listOf(
            line("购买", 500f, 460f),    // 在左侧，不属于按钮列
            line("购买", 1600f, 460f)
        )
        val pt = rowBuyPoint(lines, cy = 460f, tol = 100f, cx = 1000f)
        assertEquals(1600f, pt!!.first, 0.01f)
    }

    /* ================= 弹窗按钮定位 ================= */

    @Test
    fun dialogCancel_picks_leftmost_cancel() {
        val lines = listOf(
            line("取消", 1500f, 900f),
            line("取消", 1100f, 900f)
        )
        assertEquals(1100f, dialogCancel(lines)!!.first, 0.01f)
    }

    @Test
    fun dialogBuy_picks_nearest_buy_right_of_cancel() {
        val lines = listOf(
            line("取消", 1100f, 900f),
            line("购买", 1500f, 900f)
        )
        val pt = dialogBuy(lines, imgH = 1272)
        assertEquals(1500f, pt!!.first, 0.01f)
    }

    @Test
    fun dialogBuy_returns_null_without_cancel_key() {
        val lines = listOf(line("购买", 1500f, 900f))
        assertNull("没有取消键说明这不是购买弹窗，不能盲点", dialogBuy(lines, 1272))
    }

    @Test
    fun dialogBuy_rejects_buy_key_far_from_cancel() {
        val lines = listOf(
            line("取消", 1100f, 200f),
            line("购买", 1500f, 1100f)   // 纵向差 900，超过 imgH 的 6%
        )
        assertNull(dialogBuy(lines, imgH = 1272))
    }

    @Test
    fun refreshButton_excludes_cost_description_text() {
        val lines = listOf(
            line("更新需要消耗3个天空石", 1400f, 700f),
            line("立即更新", 1400f, 1100f)
        )
        val pt = refreshButton(lines)
        assertEquals(1100f, pt!!.second, 0.01f)
    }

    @Test
    fun refreshConfirm_picks_rightmost_confirm() {
        val lines = listOf(
            line("确认", 1200f, 900f),
            line("确认", 1600f, 900f)
        )
        assertEquals(1600f, refreshConfirm(lines)!!.first, 0.01f)
    }

    /* ================= 弹窗商品名（区分点错行与 OCR 抖动） ================= */

    @Test
    fun dialogItemKind_reads_item_name_from_dialog() {
        val lines = listOf(
            line("购买商品", 1400f, 500f),
            line("神秘奖牌", 1400f, 600f)
        )
        assertEquals("medal", dialogItemKind(lines))
    }

    @Test
    fun dialogItemKind_returns_null_instead_of_guessing() {
        val lines = listOf(line("购买商品", 1400f, 500f))
        assertNull(dialogItemKind(lines))
    }

    /* ================= 画面指纹（刷新验证） ================= */

    @Test
    fun fingerprint_changes_when_candidate_moves() {
        val a = DetectionResult(Scene.SHOP_LIST, emptyList(),
            listOf(Candidate("bookmark", 100f, 400f, 0.9f, 50f, emptyList())), emptyList(), "test", "")
        val b = DetectionResult(Scene.SHOP_LIST, emptyList(),
            listOf(Candidate("bookmark", 100f, 900f, 0.9f, 50f, emptyList())), emptyList(), "test", "")
        assertTrue("候选行位置变了，指纹必须变", frameFingerprint(a) != frameFingerprint(b))
    }

    @Test
    fun fingerprint_is_stable_for_identical_frames() {
        val mk = {
            DetectionResult(Scene.SHOP_LIST, listOf(line("购买", 1600f, 400f)),
                listOf(Candidate("bookmark", 100f, 400f, 0.9f, 50f, emptyList())), emptyList(), "test", "")
        }
        assertEquals(frameFingerprint(mk()), frameFingerprint(mk()))
    }

    @Test
    fun fingerprint_changes_when_ocr_text_changes() {
        val a = DetectionResult(Scene.SHOP_LIST, listOf(line("购买", 1600f, 400f)), emptyList(), emptyList(), "t", "")
        val b = DetectionResult(Scene.SHOP_LIST, listOf(line("售罄", 1600f, 400f)), emptyList(), emptyList(), "t", "")
        assertTrue(frameFingerprint(a) != frameFingerprint(b))
    }
}
