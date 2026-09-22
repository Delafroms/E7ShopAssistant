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

    /**
     * 运行时设置 OCR **det** 的推理线程数（2026-09-20 新增，供对比实验）。
     * 返回实际生效值；**-1 表示模型尚未加载**。
     *
     * ⚠ **只影响 det**：rec 在 `detect_and_recognize` 的任务级并行循环里
     * （`#pragma omp parallel for num_threads(ncnn::get_big_cpu_count())`），
     * 若每个并行任务内部再开多线程，总线程数会变成"大核数 × t"而严重超额，
     * 因此 native 侧刻意不对 rec 生效。
     *
     * 默认值仍是 1，生产路径不调用它。
     */
    @JvmStatic external fun nativeSetThreads(threads: Int): Int

    /** 设置 det 推理线程数（0/负数 = 回到默认 1）。返回实际生效值，-1 = 模型未加载。 */
    fun setThreads(n: Int): Int = try { nativeSetThreads(n) } catch (e: Throwable) { -1 }

    fun load(mgr: AssetManager): Boolean = try { nativeLoad(mgr) } catch (e: Throwable) { false }

    data class OcrLine(val text: String, val cx: Float, val cy: Float, val prob: Float)

    /**
     * 最近一次失败的描述（2026-09-19 新增）。
     *
     * 为什么需要：`recognize` 内部已把 Throwable 吞成 emptyList（fail-closed），
     * 调用方 Recognition 里的 try/catch 因此**永远不会进入** —— 它的
     * notePerceptionFailure 是死代码，"native 崩了但日志一片安静"的盲区又回来了。
     * 现在失败在这里留痕，调用方用 [takeFailure] 取走并上报（取走即清空，不重复报）。
     */
    @Volatile
    private var lastFail: Throwable? = null

    /** 取走并清空最近一次失败；无失败返回 null。 */
    fun takeFailure(): Throwable? {
        val f = lastFail
        lastFail = null
        return f
    }

    /** Run PP-OCRv5 on a bitmap. Empty list on any failure (fail-closed). */
    fun recognize(bmp: Bitmap): List<OcrLine> {
        val arr = try { nativeOcr(bmp) } catch (e: Throwable) {
            android.util.Log.e("PpOcr", "nativeOcr failed", e)
            lastFail = e
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
