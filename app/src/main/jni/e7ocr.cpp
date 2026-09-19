// E7SA PP-OCRv5 JNI bridge: Bitmap -> Chinese text (no camera, no drawing).
// Based on nihui/ncnn-android-ppocrv5 (BSD-3-Clause, Tencent ncnn).
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <cstring>
#include <mutex>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "ppocrv5.h"
#include "ppocrv5_dict.h"

#define TAG "E7OCR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static PPOCRv5* g_ocr = 0;
// 保护"加载"这一段：装备评分页每次进入都会调 nativeLoad，而机器人线程可能正在
// g_ocr 上跑推理。旧版无条件 delete 再 new → use-after-free → SIGSEGV（Kotlin 捕不到）。
static std::mutex g_ocr_mutex;

// OpenMP dispatch symbols for opencv's parallel.cpp (simpleomp doesn't
// provide them). With cv::setNumThreads(1) opencv runs loops inline and
// NEVER calls these. If some path ever raises the thread count, these
// implementations degrade the loop to ONE single chunk (the full range
// executed exactly once by one thread) instead of "zero work" - the old
// all-return-0 stubs made dispatch_next report "no work", so parallel
// loops (cv::resize etc.) never executed their body and produced ALL-ZERO
// output - the root cause of "on-device PP-OCR/YOLO return 0 results".
extern "C" {
static long g_disp_lower = 0;
static long g_disp_upper = -1;
static long g_disp_stride = 1;

int32_t __kmpc_dispatch_init_4(void*, int32_t, int32_t nlower, int32_t nupper, int32_t nstride, int32_t, int32_t)
{
    g_disp_lower = nlower; g_disp_upper = nupper; g_disp_stride = nstride;
    return 0;
}
int32_t __kmpc_dispatch_init_4u(void*, int32_t, uint32_t nlower, uint32_t nupper, uint32_t nstride, uint32_t, uint32_t)
{
    g_disp_lower = nlower; g_disp_upper = nupper; g_disp_stride = nstride;
    return 0;
}
int32_t __kmpc_dispatch_next_4(void*, int32_t, int32_t* p_last, int32_t* p_lower, int32_t* p_upper, int32_t* p_stride)
{
    if (g_disp_upper < g_disp_lower)
    {
        *p_last = 1;
        return 0;   // exhausted
    }
    *p_lower = (int32_t)g_disp_lower;
    *p_upper = (int32_t)g_disp_upper;
    *p_stride = (int32_t)g_disp_stride;
    g_disp_upper = -1;   // hand out exactly one chunk, then done
    *p_last = 1;
    return 1;
}
int32_t __kmpc_dispatch_next_4u(void*, int32_t, int32_t* p_last, uint32_t* p_lower, uint32_t* p_upper, uint32_t* p_stride)
{
    if (g_disp_upper < g_disp_lower)
    {
        *p_last = 1;
        return 0;   // exhausted
    }
    *p_lower = (uint32_t)g_disp_lower;
    *p_upper = (uint32_t)g_disp_upper;
    *p_stride = (uint32_t)g_disp_stride;
    g_disp_upper = -1;   // hand out exactly one chunk, then done
    *p_last = 1;
    return 1;
}
void __kmpc_dispatch_deinit(void*, int32_t) {}
void __kmpc_dispatch_fini_4u(void*, int32_t) {}
void __kmpc_dispatch_fini_4(void*, int32_t) {}
}

// 【历史遗留，2026-09-19 补注】以下 setenv 是"真 libomp"时代为规避
// __kmp_affinity_initialize -> __kmp_debug_assert -> SIGABRT 而加的。
// 现状：CMakeLists 已把 libomp 从链接线剔除（改用 ncnn 自带的 simpleomp，
// 见 CMakeLists 里 -fopenmp 与 --allow-multiple-definition 的说明），
// 因此这几行现在是**无副作用的防御性设置**（simpleomp 不读这些环境变量）。
// 保留而非删除的理由：万一将来链接回真 libomp，这层保护还在；
// 在此注明，是为了终止"注释与构建现状不符"继续误导后续排查。
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("OMP_NUM_THREADS", "1", 1);
    setenv("KMP_SETTINGS", "0", 1);
    // opencv's parallel_for dispatches to OpenMP (libomp) -> the affinity
    // crash. Single-thread opencv runs its loops inline WITHOUT touching
    // __kmpc_dispatch_*, so libomp is never initialised.
    cv::setNumThreads(1);
    // ncnn single-thread is set per-Net in ppocrv5.cpp / yolov8.cpp load()
    // (opt.num_threads = 1) - that is the crash-proof path.
    return JNI_VERSION_1_6;
}

extern "C" {

// Java_com_e7_shop_bot_PpOcr_nativeLoad(AssetManager) -> boolean
JNIEXPORT jboolean JNICALL Java_com_e7_shop_bot_PpOcr_nativeLoad(JNIEnv* env, jobject thiz, jobject assetManager)
{
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == 0)
    {
        LOGE("nativeLoad: AAssetManager null");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_ocr_mutex);

    // 幂等：已经加载过就直接返回，**绝不 delete**。
    // 旧版每次 nativeLoad 都 delete g_ocr 再 new —— 而 EquipmentScoreActivity 每次
    // 进入页面都会调 PpOcr.load()，此时机器人线程可能正在 g_ocr 上执行
    // detect_and_recognize → 释放正在使用的对象 → SIGSEGV（native 崩溃，Kotlin
    // 的 try/catch 捕不到，进程直接死）。
    if (g_ocr != 0)
    {
        LOGI("nativeLoad: already loaded, skip reload");
        return JNI_TRUE;
    }

    PPOCRv5* inst = new PPOCRv5;
    // mobile models, fp16 (mobile is fp16-safe), CPU only (small models are
    // faster on CPU than GPU - ncnn README).
    int ret = inst->load(mgr,
        "PP_OCRv5_mobile_det.ncnn.param", "PP_OCRv5_mobile_det.ncnn.bin",
        "PP_OCRv5_mobile_rec.ncnn.param", "PP_OCRv5_mobile_rec.ncnn.bin",
        true, false);

    if (ret != 0)
    {
        // load() 现在会返回真实的失败码（旧版恒返回 0，这个分支是死代码）
        LOGE("nativeLoad: load failed ret=%d", ret);
        delete inst;
        return JNI_FALSE;
    }

    // 发布点：只有**加载完成**的对象才会被其他线程看到，
    // 避免"指针已非空但模型还没就绪"时另一个线程在上面跑推理。
    g_ocr = inst;
    LOGI("nativeLoad: PP-OCRv5 mobile loaded OK");
    return JNI_TRUE;
}

// Java_com_e7_shop_bot_PpOcr_nativeOcr(Bitmap) -> String[]
// Returns one string per detected text box: "text\tcx\tcy\tprob"
// (cx,cy = box center in ORIGINAL bitmap pixels; text = recognized Chinese).
JNIEXPORT jobjectArray JNICALL Java_com_e7_shop_bot_PpOcr_nativeOcr(JNIEnv* env, jobject thiz, jobject bitmap)
{
    if (g_ocr == 0)
    {
        LOGE("nativeOcr: model not loaded");
        return 0;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("nativeOcr: getInfo failed");
        return 0;
    }

    void* pixels = 0;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("nativeOcr: lockPixels failed");
        return 0;
    }

    // STRIDE-SAFE: Android bitmaps may pad each row (info.stride > width*4,
    // common for hardware-buffer copies). Wrapping pixels directly into
    // cv::Mat(h, w, CV_8UC4) ignores the padding and shears the image row by
    // row -> det sees garbage and returns 0 boxes (the on-device "OCR reads
    // nothing" bug). Copy into a tightly packed buffer first.
    LOGI("nativeOcr: fmt=%d stride=%d w=%d h=%d", info.format, info.stride, info.width, info.height);
    const size_t tight_row = (size_t)info.width * 4;
    std::vector<unsigned char> tight(info.height * tight_row);
    const unsigned char* src = (const unsigned char*)pixels;
    for (uint32_t y = 0; y < info.height; y++)
        memcpy(tight.data() + y * tight_row, src + (size_t)y * info.stride, tight_row);
    AndroidBitmap_unlockPixels(env, bitmap);

    // RGBA_8888 -> RGB
    cv::Mat rgba(info.height, info.width, CV_8UC4, tight.data());
    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);

    std::vector<Object> objects;
    int ret = g_ocr->detect_and_recognize(rgb, objects);

    if (ret != 0)
    {
        LOGE("nativeOcr: detect_and_recognize failed ret=%d", ret);
        return 0;
    }

    // build strings: text + box center
    std::vector<std::string> results;
    for (size_t i = 0; i < objects.size(); i++)
    {
        const Object& obj = objects[i];

        std::string text;
        for (size_t j = 0; j < obj.text.size(); j++)
        {
            const Character& ch = obj.text[j];
            if (ch.id >= character_dict_size)
                continue;
            text += character_dict[ch.id];
        }

        if (text.empty())
            continue;

        char buf[512];
        snprintf(buf, sizeof(buf), "%s\t%d\t%d\t%.3f",
                 text.c_str(),
                 (int)obj.rrect.center.x,
                 (int)obj.rrect.center.y,
                 (float)obj.prob);
        results.push_back(std::string(buf));
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)results.size(), stringClass, 0);
    for (size_t i = 0; i < results.size(); i++)
    {
        jstring js = env->NewStringUTF(results[i].c_str());
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }

    return arr;
}

} // extern "C"
