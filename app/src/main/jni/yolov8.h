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

#ifndef YOLOV8_H
#define YOLOV8_H

#include <opencv2/core/core.hpp>

#include <net.h>

struct KeyPoint
{
    cv::Point2f p;
    float prob;
};

struct Object
{
    cv::Rect_<float> rect;
    cv::RotatedRect rrect;
    int label;
    float prob;
    int gindex;
    cv::Mat mask;
    std::vector<KeyPoint> keypoints;
};

class YOLOv8
{
public:
    virtual ~YOLOv8();

    int load(const char* parampath, const char* modelpath, bool use_gpu = false);
    int load(AAssetManager* mgr, const char* parampath, const char* modelpath, bool use_gpu = false);

    void set_det_target_size(int target_size);

    /**
     * 运行时设置推理线程数（2026-09-20 新增，用于"线程数 vs 单帧耗时"对比实验）。
     *
     * 默认仍是 1（在 load 里设置）。单线程原本是为规避**真 libomp** 的
     * __kmp_affinity_initialize 崩溃；现在链接的是 ncnn 自带的 simpleomp
     * （NCNN_SIMPLEOMP=ON，无 affinity 代码），那条崩溃路径已不存在。
     *
     * ⚠ 只影响 **ncnn 算子**的并行度。**OpenCV 仍必须保持单线程** ——
     * simpleomp 不提供 opencv parallel.cpp 需要的 dispatch 符号，提高
     * cv::setNumThreads 会让并行循环退化（历史上曾导致"全零输出"），
     * 见 e7ocr.cpp 顶部关于 __kmpc_dispatch_* 桩的说明。两者不要混为一谈。
     */
    void set_num_threads(int threads);

    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects) = 0;
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects) = 0;

protected:
    ncnn::Net yolov8;
    // 默认值必须在这里给：旧版没有构造函数，唯一的初始化却写在**析构函数**里
    // （yolov8.cpp 的 YOLOv8::~YOLOv8(){ det_target_size = 320; }），
    // 一旦调用顺序变化（set_det_target_size 之前就用）就是不确定值进 letterbox 运算。
    int det_target_size = 320;
};

class YOLOv8_det : public YOLOv8
{
public:
    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects);
};

class YOLOv8_det_coco : public YOLOv8_det
{
public:
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
};

class YOLOv8_det_oiv7 : public YOLOv8_det
{
public:
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
};

class YOLOv8_seg : public YOLOv8
{
public:
    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects);
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
};

class YOLOv8_pose : public YOLOv8
{
public:
    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects);
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
};

class YOLOv8_cls : public YOLOv8
{
public:
    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects);
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
};

class YOLOv8_obb : public YOLOv8
{
public:
    virtual int detect(const cv::Mat& rgb, std::vector<Object>& objects);
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
};

#endif // YOLOV8_H
