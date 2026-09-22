// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2024 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include "yolov8.h"

YOLOv8::~YOLOv8()
{
    // 原实现在这里给 det_target_size 赋初值（显然是笔误：析构里赋值毫无意义）。
    // 默认值已移到头文件的成员初始化器，见 yolov8.h。
}

int YOLOv8::load(const char* parampath, const char* modelpath, bool use_gpu)
{
    yolov8.clear();

    yolov8.opt = ncnn::Option();
    yolov8.opt.num_threads = 1;   // single-thread: avoids libomp affinity crash on Android

#if NCNN_VULKAN
    yolov8.opt.use_vulkan_compute = use_gpu;
#endif

    int ret = yolov8.load_param(parampath);
    if (ret != 0) return ret;
    ret = yolov8.load_model(modelpath);
    if (ret != 0) return ret;

    return 0;
}

int YOLOv8::load(AAssetManager* mgr, const char* parampath, const char* modelpath, bool use_gpu)
{
    yolov8.clear();

    yolov8.opt = ncnn::Option();
    yolov8.opt.num_threads = 1;   // single-thread: avoids libomp affinity crash on Android

#if NCNN_VULKAN
    yolov8.opt.use_vulkan_compute = use_gpu;
#endif

    // 返回值必须透出（2026-09-18 修复）：旧版恒 return 0，导致 JNI 层的失败分支
    // 是死代码 —— 模型缺失/损坏时照样上报 loaded=true，表现为"识别 0 结果但日志说模型好"。
    int ret = yolov8.load_param(mgr, parampath);
    if (ret != 0) return ret;
    ret = yolov8.load_model(mgr, modelpath);
    if (ret != 0) return ret;

    return 0;
}

void YOLOv8::set_det_target_size(int target_size)
{
    det_target_size = target_size;
}

void YOLOv8::set_num_threads(int threads)
{
    // 只改 ncnn 的算子并行度（见头文件里的说明：与 OpenCV 的单线程约束无关）。
    // 非正值一律回到默认 1，避免误传 0 导致 ncnn 用"自动"线程数而偏离预期。
    yolov8.opt.num_threads = (threads > 0) ? threads : 1;
}
