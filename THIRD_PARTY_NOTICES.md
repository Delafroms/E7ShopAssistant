# Third-Party Notices

E7SA 使用以下第三方组件。各组件的版权与许可归其各自权利人所有。

## 原生层

| 组件 | 许可 | 用途 |
|---|---|---|
| [ncnn](https://github.com/Tencent/ncnn)（Tencent） | BSD 3-Clause | 神经网络推理（OCR 与目标检测） |
| [opencv-mobile](https://github.com/nihui/opencv-mobile) 4.13.0 | Apache License 2.0 | 图像预处理与几何变换 |
| [PP-OCRv5 mobile det/rec](https://github.com/PaddlePaddle/PaddleOCR)（PaddlePaddle） | Apache License 2.0 | 中文文本检测与识别模型 |

## 应用层

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose / Material Components | Apache License 2.0 |
| Kotlin 标准库 / kotlinx.coroutines | Apache License 2.0 |
| OkHttp / Okio | Apache License 2.0 |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API)（可选，未安装时不启用） | Apache License 2.0 |

## 模型

内置目标检测模型 `app/src/main/assets/e7sa_yolo.ncnn.{param,bin,meta}` 使用
[Ultralytics](https://www.ultralytics.com/license) 工具链训练。Ultralytics YOLO 以 **AGPL-3.0**
发布，其免费使用的前提是将整个项目以 AGPL-3.0 开源 —— **本项目正是据此以 AGPL-3.0 发布**
（见 `LICENSE`）。若你需要以闭源或商业专有方式使用该模型，须自行向 Ultralytics 取得企业许可。

## 游戏素材与商标

- **本项目不包含《第七史诗》（Epic Seven）的任何官方素材**。早期版本内置过官方 Logo，
  已于 v1.0.0 移除；顶部标识改为文字标题或用户自行上传的图片。
- 《第七史诗》/ Epic Seven 的名称、商标与游戏内容归 **Smilegate Holdings / Smilegate Megaport** 所有。
  本项目与其无任何隶属、赞助或背书关系。
- 用户自行上传的图片由用户自行负责其权利来源。
