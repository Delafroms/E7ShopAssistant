package com.e7.shop.bot

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * PP-OCRv5 (ncnn) Chinese OCR bridge - the FAST replacement for ML Kit.
 *
 * C++ side: app/src/main/jni/e7ocr.cpp (JNI bridge) + ppocrv5.cpp (det+rec).
 * Models: assets/PP_OCRv5_mobile_{det,rec}.ncnn.{param,bin} (~10.6MB total).
 *
 * Native methods map to:
 *   Java_com_e7_shop_bot_PpOcr_nativeLoad
 *   Java_com_e7_shop_bot_PpOcr_nativeOcr
 */
object PpOcr {

    init {
        try {
            System.loadLibrary("e7ocr")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("PpOcr", "loadLibrary failed (native build missing?)", e)
        }
    }

    @JvmStatic external fun nativeLoad(assetManager: AssetManager): Boolean

    /** Returns one string per text box: "text\tcx\tcy\tprob" (original pixels). */
    @JvmStatic external fun nativeOcr(bitmap: Bitmap): Array<String>?

    fun load(mgr: AssetManager): Boolean = try { nativeLoad(mgr) } catch (e: Throwable) { false }

    data class OcrLine(val text: String, val cx: Float, val cy: Float, val prob: Float)

    /** Run PP-OCRv5 on a bitmap. Empty list on any failure (fail-closed). */
    fun recognize(bmp: Bitmap): List<OcrLine> {
        val arr = try { nativeOcr(bmp) } catch (e: Throwable) {
            android.util.Log.e("PpOcr", "nativeOcr failed", e)
            null
        } ?: return emptyList()
        return arr.mapNotNull { s ->
            val parts = s.split("\t")
            if (parts.size >= 4) {
                OcrLine(parts[0], parts[1].toFloatOrNull() ?: 0f, parts[2].toFloatOrNull() ?: 0f, parts[3].toFloatOrNull() ?: 0f)
            } else null
        }
    }
}
