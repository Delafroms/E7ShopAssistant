// E7SA YOLOv8 JNI bridge: Bitmap -> detected bookmark/medal boxes.
// Based on nihui/ncnn-android-yolov8 (BSD-3-Clause, Tencent ncnn).
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <cstring>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "yolov8.h"

#define TAG "E7YOLO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static YOLOv8* g_yolo = 0;

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

    if (g_yolo) { delete g_yolo; g_yolo = 0; }

    g_yolo = new YOLOv8_det_coco;
    int ret = g_yolo->load(mgr, "e7sa_yolo.ncnn.param", "e7sa_yolo.ncnn.bin", false);
    if (ret != 0)
    {
        LOGE("nativeLoad: load failed ret=%d", ret);
        delete g_yolo; g_yolo = 0;
        return JNI_FALSE;
    }
    const int tsize = (targetSize > 0) ? (int)targetSize : 640;
    g_yolo->set_det_target_size(tsize);
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

} // extern "C"
