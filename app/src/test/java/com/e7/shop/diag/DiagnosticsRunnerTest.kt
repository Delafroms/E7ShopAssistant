package com.e7.shop.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归台标注解析单测。
 *
 * 为什么值得测：回归台是**模型迭代的唯一裁判**。标注解析错了，
 * 召回率/误检数就全是假的，会让团队按错误信号去调模型。
 * 解析兼容两种标注格式（cx/cy 与 bbox），两种都必须正确。
 */
class DiagnosticsRunnerTest {

    @Test
    fun parses_cx_cy_form() {
        val json = """{"targets":[{"kind":"bookmark","cx":100.5,"cy":420.0}]}"""
        val gt = DiagnosticsRunner.parseGtText(json)
        assertEquals(1, gt!!.size)
        assertEquals("bookmark", gt[0].kind)
        assertEquals(420, gt[0].cy)
    }

    @Test
    fun parses_bbox_form_by_deriving_center() {
        val json = """{"targets":[{"kind":"medal","bbox":{"x":10,"y":100,"width":20,"height":40}}]}"""
        val gt = DiagnosticsRunner.parseGtText(json)
        assertEquals(1, gt!!.size)
        // cy = y + height/2 = 100 + 20 = 120
        assertEquals(120, gt[0].cy)
    }

    @Test
    fun prefers_explicit_cy_over_bbox() {
        val json = """{"targets":[{"kind":"medal","cx":5,"cy":300,"bbox":{"x":0,"y":0,"width":10,"height":10}}]}"""
        val gt = DiagnosticsRunner.parseGtText(json)
        assertEquals(300, gt!![0].cy)
    }

    @Test
    fun parses_multiple_targets() {
        val json = """{"targets":[{"kind":"bookmark","cy":100},{"kind":"medal","cy":400}]}"""
        val gt = DiagnosticsRunner.parseGtText(json)
        assertEquals(2, gt!!.size)
    }

    @Test
    fun reads_optional_price() {
        val json = """{"targets":[{"kind":"bookmark","cy":100,"price":184000}]}"""
        val gt = DiagnosticsRunner.parseGtText(json)
        assertEquals(184000L, gt!![0].price)
    }

    @Test
    fun missing_price_defaults_to_zero() {
        val json = """{"targets":[{"kind":"bookmark","cy":100}]}"""
        val gt = DiagnosticsRunner.parseGtText(json)
        assertEquals(0L, gt!![0].price)
    }

    @Test
    fun malformed_json_returns_null_instead_of_throwing() {
        assertNull("坏标注不能中断整轮回归", DiagnosticsRunner.parseGtText("{not json"))
    }

    @Test
    fun missing_targets_array_returns_null() {
        assertNull(DiagnosticsRunner.parseGtText("""{"other":1}"""))
    }

    @Test
    fun empty_targets_returns_empty_list() {
        val gt = DiagnosticsRunner.parseGtText("""{"targets":[]}""")
        assertTrue(gt!!.isEmpty())
    }
}
