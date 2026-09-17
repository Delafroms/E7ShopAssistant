cmake_minimum_required(VERSION 3.10)
project(e7ocr)

set(CMAKE_CXX_STANDARD 11)

# ---- OpenCV (opencv-mobile, headers + static libs) ----
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.13.0-android/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

# STRIP opencv's propagated OpenMP link flags: our simpleomp ncnn already
# provides __kmpc_*, and the real libomp SIGABRTs on device
# (__kmp_affinity_initialize). Removing -fopenmp/-static-openmp keeps libomp
# completely OUT of the link line.
foreach(_ocv_tgt opencv_core opencv_imgproc)
    get_target_property(_ocv_opt ${_ocv_tgt} INTERFACE_LINK_OPTIONS)
    if(_ocv_opt)
        string(REPLACE "-fopenmp" "" _ocv_opt "${_ocv_opt}")
        string(REPLACE "-static-openmp" "" _ocv_opt "${_ocv_opt}")
        set_target_properties(${_ocv_tgt} PROPERTIES INTERFACE_LINK_OPTIONS "${_ocv_opt}")
    endif()
    # ALSO strip from INTERFACE_LINK_LIBRARIES: -fopenmp lives there in this
    # build and pulls the real libomp whose __kmp_fatal crashes when any
    # symbol simpleomp doesn't provide gets resolved to it.
    get_target_property(_ocv_libs ${_ocv_tgt} INTERFACE_LINK_LIBRARIES)
    if(_ocv_libs)
        string(REPLACE "-fopenmp" "" _ocv_libs "${_ocv_libs}")
        string(REPLACE "-static-openmp" "" _ocv_libs "${_ocv_libs}")
        set_target_properties(${_ocv_tgt} PROPERTIES INTERFACE_LINK_LIBRARIES "${_ocv_libs}")
    endif()
endforeach()

# ---- ncnn (prebuilt static lib, per-ABI) ----
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(NCNN_ROOT ${CMAKE_SOURCE_DIR}/ncnn-arm64-v8a)
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(NCNN_ROOT ${CMAKE_SOURCE_DIR}/ncnn-armeabi-v7a)
else()
    message(FATAL_ERROR "unsupported ABI: ${ANDROID_ABI}")
endif()

include_directories(${NCNN_ROOT}/include/ncnn)

# ncnn STATIC, compiled with NCNN_SIMPLEOMP=ON (self-contained lightweight
# OpenMP, NO libomp). The stock prebuilt libncnn used real libomp whose
# __kmp_affinity_initialize SIGABRTs on this device - simpleomp has no
# affinity code and is the crash-proof choice.
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_ROOT}/lib/libncnn.a)

# ---- E7SA OCR + YOLO bridges ----
add_library(e7ocr SHARED e7ocr.cpp ppocrv5.cpp e7yolo.cpp yolov8.cpp yolov8_det.cpp)

# ppocrv5.cpp 里有一条 "#pragma omp parallel for"（识别循环并行化）。
#
# 早期这里显式传 -fno-openmp 把它变成空操作，理由是"文本框不多、串行够快"——
# 但实测商店截图有 60~80 个文本行，OCR 恰恰是识别耗时的大头
# （实测总 631ms 里 OCR 约占 390ms）。当初禁用的真实动机是怕**真 libomp** 的
# __kmp_affinity_initialize SIGABRT，而我们现在链接的是 ncnn 自带的
# **simpleomp**（NCNN_SIMPLEOMP=ON，自带 __kmpc_*），不存在那条崩溃路径。
# 所以这里打开并行，让识别循环真正吃到多核。
set_source_files_properties(ppocrv5.cpp PROPERTIES COMPILE_OPTIONS "-fopenmp")

# ncnn SHARED already embeds libomp (no separate libomp link - it caused the
# __kmp_affinity_initialize SIGABRT on device).
# - jnigraphics: AndroidBitmap_getInfo/lockPixels
# - c++_static + c++abi: libncnn.so C++ symbols (operator new, __cxa_guard)
target_link_libraries(e7ocr ncnn ${OpenCV_LIBS} jnigraphics android log c++_static c++abi)

# opencv-mobile propagates -fopenmp (real libomp) while our simpleomp ncnn
# already provides __kmpc_*. Let the FIRST definition (simpleomp, linked
# first) win so the crash-prone libomp path is never used.
target_link_options(e7ocr PRIVATE -Wl,--allow-multiple-definition)

# ppocrv5.cpp contains "#pragma omp parallel for" - without OpenMP this is a
# no-op (serial recognize), which is fine for our static shop screens.
