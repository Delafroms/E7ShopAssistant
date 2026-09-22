// E7SA YOLOv8 JNI bridge: Bitmap -> detected bookmark/medal boxes.
// Based on nihui/ncnn-android-yolov8 (BSD-3-Clause, Tencent ncnn).
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

#include "yolov8.h"

#define TAG "E7YOLO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static YOLOv8* g_yolo = 0;
// 与 e7ocr.cpp 同理：加载必须串行且幂等，绝不 delete 正在被推理使用的对象。
static std::mutex g_yolo_mutex;

extern "C" {

// Java_com_e7_shop_bot_YoloDet_nativeLoad(AssetManager, int targetSize) -> boolean
// Model file names are fixed: e7sa_yolo.ncnn.param / e7sa_yolo.ncnn.bin
// targetSize comes from the model's self-describing meta file (e7sa_yolo.ncnn.meta):
// it MUST match the imgsz used when exporting that model. Measured: a 640-exported
// model run at 1280 drops recall 100% -> 14.3% (letterbox scale mismatch).
JNIEXPORT jboolean JNICALL Java_com_e7_shop_bot_YoloDet_nativeLoad(JNIEnv* env, jobject thiz, jobject assetManager, jint targetSize)
{
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == 0) { LOGE("nativeLoad: AAssetManager null"); return JNI_FALSE; }

    std::lock_guard<std::mutex> lock(g_yolo_mutex);

    // 幂等：已加载直接返回，绝不 delete（机器人线程可能正在 g_yolo 上推理）
    if (g_yolo != 0)
    {
        LOGI("nativeLoad: already loaded, skip reload");
        return JNI_TRUE;
    }

    YOLOv8_det_coco* inst = new YOLOv8_det_coco;
    int ret = inst->load(mgr, "e7sa_yolo.ncnn.param", "e7sa_yolo.ncnn.bin", false);
    if (ret != 0)
    {
        // load() 现在返回真实的失败码（旧版恒返回 0 → 这里曾是死代码，
        // 模型缺失时依然上报 loaded=true，现象是"识别 0 结果但日志说模型是好的"）
        LOGE("nativeLoad: load failed ret=%d", ret);
        delete inst;
        return JNI_FALSE;
    }
    const int tsize = (targetSize > 0) ? (int)targetSize : 640;
    // 必须先设好尺寸再发布指针：否则另一个线程可能拿到未设尺寸的实例
    inst->set_det_target_size(tsize);
    g_yolo = inst;
    LOGI("nativeLoad: YOLOv8 detector loaded OK (target_size=%d)", tsize);
    return JNI_TRUE;
}

// Java_com_e7_shop_bot_YoloDet_nativeDetect(Bitmap) -> String[]
// Each string: "class\tcx\tcy\tw\th\tprob" in ORIGINAL bitmap pixels.
// class: 0=bookmark 1=medal (per classes.txt)
JNIEXPORT jobjectArray JNICALL Java_com_e7_shop_bot_YoloDet_nativeDetect(JNIEnv* env, jobject thiz, jobject bitmap)
{
    if (g_yolo == 0) { LOGE("nativeDetect: model not loaded"); return 0; }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) { LOGE("getInfo failed"); return 0; }

    void* pixels = 0;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) { LOGE("lockPixels failed"); return 0; }

    // STRIDE-SAFE: see nativeOcr in e7ocr.cpp - Android bitmaps may pad each
    // row; wrapping pixels directly shears the image and the detector sees
    // garbage (0 detections). Copy into a tightly packed buffer first.
    LOGI("nativeDetect: fmt=%d stride=%d w=%d h=%d", info.format, info.stride, info.width, info.height);
    const size_t tight_row = (size_t)info.width * 4;
    std::vector<unsigned char> tight(info.height * tight_row);
    const unsigned char* src = (const unsigned char*)pixels;
    for (uint32_t y = 0; y < info.height; y++)
        memcpy(tight.data() + y * tight_row, src + (size_t)y * info.stride, tight_row);
    AndroidBitmap_unlockPixels(env, bitmap);

    cv::Mat rgba(info.height, info.width, CV_8UC4, tight.data());
    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);

    std::vector<Object> objects;
    int ret = g_yolo->detect(rgb, objects);

    if (ret != 0) { LOGE("detect failed ret=%d", ret); return 0; }

    std::vector<std::string> results;
    for (size_t i = 0; i < objects.size(); i++)
    {
        const Object& obj = objects[i];
        char buf[256];
        snprintf(buf, sizeof(buf), "%d\t%.1f\t%.1f\t%.1f\t%.1f\t%.3f",
                 obj.label,
                 obj.rect.x + obj.rect.width / 2.f,
                 obj.rect.y + obj.rect.height / 2.f,
                 obj.rect.width,
                 obj.rect.height,
                 obj.prob);
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

// Java_com_e7_shop_bot_YoloDet_nativeSetThreads(int threads) -> int
// 运行时切换 YOLO 推理线程数（诊断对比用；生产默认值仍是 1）。
// 返回实际生效的线程数；-1 表示模型尚未加载（设置无效，调用方可据此判断）。
JNIEXPORT jint JNICALL Java_com_e7_shop_bot_YoloDet_nativeSetThreads(JNIEnv* env, jobject thiz, jint threads)
{
    std::lock_guard<std::mutex> lock(g_yolo_mutex);
    if (g_yolo == 0) { LOGE("nativeSetThreads: model not loaded"); return -1; }
    const int t = (threads > 0) ? (int)threads : 1;
    g_yolo->set_num_threads(t);
    LOGI("nativeSetThreads: num_threads=%d", t);
    return t;
}

} // extern "C"
