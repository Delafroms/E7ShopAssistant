package com.e7.shop.bot

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * YOLOv8 (ncnn) 目标检测桥（图标 + 按钮多分类）。
 *
 * 模型是**自描述**的：assets 里除 param/bin 外还有一个 meta 文件
 * （`e7sa_yolo.ncnn.meta`），写明推理输入尺寸与类别表。换模型只需替换这三个文件，
 * 不用改 Kotlin、不用改 C++、不用重编译。
 *
 * 为什么尺寸必须由模型自己声明：推理输入尺寸必须与导出该模型时的 imgsz 一致。
 * 实测把一个 640 导出的模型按 1280 推理，图标召回从 100% 掉到 14.3%
 * （letterbox 缩放不匹配、锚点错位）。硬编码在代码里迟早踩这个坑。
 *
 * 类别表同理：2 类图标模型与 7 类多分类模型的 cls 含义不同，
 * 写死 `if (cls == 0) bookmark else medal` 会让按钮类被误判成奖牌。
 *
 * C++ 侧: app/src/main/jni/e7yolo.cpp + yolov8.cpp/yolov8_det.cpp
 * （后处理 `num_class = pred.w - 4` 是自适应的，类别数变化无需改 C++）
 */
object YoloDet {

    private const val META_ASSET = "e7sa_yolo.ncnn.meta"
    private const val DEFAULT_IMGSZ = 640
    private val DEFAULT_CLASSES = listOf("bookmark", "medal")

    init {
        try {
            System.loadLibrary("e7ocr")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("YoloDet", "loadLibrary failed", e)
        }
    }

    /** 由 meta 的 imgsz 决定推理输入尺寸（0 或负数则 native 侧退回 640）。 */
    @JvmStatic external fun nativeLoad(assetManager: AssetManager, targetSize: Int): Boolean

    /** 模型加载状态（服务启动时设置；引擎选择据此决定是否回退传统引擎）。 */
    @Volatile
    var loaded: Boolean = false
        private set

    /** 推理输入尺寸（来自 meta；未声明时 640）。 */
    @Volatile
    var imgsz: Int = DEFAULT_IMGSZ
        private set

    /** 类别表（来自 meta，下标 = class id）。 */
    @Volatile
    var classes: List<String> = DEFAULT_CLASSES
        private set

    /** Returns one string per box: "class\tcx\tcy\tw\th\tprob" (original pixels). */
    @JvmStatic external fun nativeDetect(bitmap: Bitmap): Array<String>?

    fun load(mgr: AssetManager): Boolean {
        readMeta(mgr)
        val ok = try { nativeLoad(mgr, imgsz) } catch (e: Throwable) { false }
        loaded = ok
        if (ok) {
            android.util.Log.i("YoloDet", "model loaded imgsz=$imgsz classes=${classes.joinToString(",")}")
        }
        return ok
    }

    /**
     * 读模型自描述文件。缺失或格式不对时退回"640 + 2 类图标"的旧默认值
     * （fail-safe：宁可按旧模型处理，也不要拿着错尺寸去推理）。
     */
    private fun readMeta(mgr: AssetManager) {
        try {
            val text = mgr.open(META_ASSET).use { it.readBytes().decodeToString() }
            var size = DEFAULT_IMGSZ
            var cls: List<String>? = null
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty() || !line.contains('=')) continue
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').trim()
                when (key) {
                    "imgsz" -> value.toIntOrNull()?.let { if (it >= 160 && it % 32 == 0) size = it }
                    "classes" -> cls = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            imgsz = size
            if (!cls.isNullOrEmpty()) classes = cls
        } catch (e: Exception) {
            android.util.Log.w(
                "YoloDet",
                "meta 不可读，按默认 imgsz=$DEFAULT_IMGSZ classes=${DEFAULT_CLASSES.joinToString(",")} 处理"
            )
        }
    }

    data class Box(val cls: Int, val cx: Float, val cy: Float, val w: Float, val h: Float, val prob: Float) {
        /** 类别名（取自模型 meta 的类别表；越界时 unknown，不猜）。 */
        val clsName: String get() = classes.getOrElse(cls) { "unknown" }

        /** 是否商品图标类（bookmark / medal）。 */
        val isIcon: Boolean get() = clsName == "bookmark" || clsName == "medal"
    }

    /**
     * 最近一次推理失败的异常（2026-09-19 新增，与 PpOcr.takeFailure 同因）：
     * detect 内部已把 Throwable 吞成 emptyList，调用方的 try/catch 永不进入 →
     * 失败留痕必须由这里提供，否则"native 崩了但日志一片安静"。
     */
    @Volatile
    private var lastFail: Throwable? = null

    /** 取走并清空最近一次失败；无失败返回 null。 */
    fun takeFailure(): Throwable? {
        val f = lastFail
        lastFail = null
        return f
    }

    /** Run YOLO detection. Empty list on any failure (fail-closed). */
    fun detect(bmp: Bitmap): List<Box> {
        val arr = try { nativeDetect(bmp) } catch (e: Throwable) {
            android.util.Log.e("YoloDet", "nativeDetect failed", e)
            lastFail = e
            null
        } ?: return emptyList()
        return arr.mapNotNull { s ->
            val p = s.split("\t")
            if (p.size >= 6) Box(p[0].toIntOrNull() ?: -1, p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f,
                p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f, p[5].toFloatOrNull() ?: 0f)
            else null
        }.filter { it.cls >= 0 }
    }
}
