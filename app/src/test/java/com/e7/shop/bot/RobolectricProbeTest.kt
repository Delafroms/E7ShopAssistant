package com.e7.shop.bot

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.e7.shop.data.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric 探针：确认"电脑上的影子 Android"确实能给到我们缺的两样东西。
 *
 * 这两样正是 FSM 驱动测试的前置条件：
 *  ① 真实 Bitmap（引擎必须拿到非 null 的截图才会推进）
 *  ② 真实 Context（AppConfig 的构造依赖 SharedPreferences）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricProbeTest {

    @Test
    fun bitmap_is_real() {
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        assertEquals(64, bmp.width)
        assertEquals(32, bmp.height)
        bmp.setPixel(1, 1, android.graphics.Color.RED)
        assertEquals(android.graphics.Color.RED, bmp.getPixel(1, 1))
    }

    @Test
    fun app_config_is_constructible() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = AppConfig(ctx)
        cfg.maxSkystones = 123
        assertEquals(123, AppConfig(ctx).maxSkystones)   // 真的落到 SharedPreferences
        assertNotNull(cfg)
    }
}
