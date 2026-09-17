# E7ShopAssistant (E7SA) — 第七史诗·神秘商店智能助手

<div align="center">

**无需 Root · 全分辨率自适应 · 双引擎识图 · 识别/决策/操作分层防误触**

**V1 正式版**

</div>

---

## 这是什么

E7ShopAssistant 是一个 Android 无障碍服务应用，专为 **《第七史诗》(Epic Seven)** 国际服的**神秘商店**（Secret Shop）设计，自动完成：

- 🔍 **识别并购买「圣约书签」**（Covenant Bookmarks，184,000 金币）
- 🔍 **识别并购买「神秘奖牌」**（Mystic Medals，280,000 金币）
- 🔄 **自动刷新商店**（消耗 3 天空石，受预算保护）
- 📱 **买后自动下滑检查第 6 格**，不漏任何目标
- ♻️ **中断自动恢复**：任何时刻重新点「开始」，都会结束上一轮任务、清空本轮统计、从当前画面重新识别并自动恢复（残留弹窗自动关闭）

## 为什么而生

E7 神秘商店每小时自然刷新一次，但**书签和奖牌的出现概率极低**。市面上大部分脚本助手**需要 Root**、或**只支持 1280×720 固定分辨率**、且闭源收费无法审计。E7SA 的三个初衷：

1. **免 Root**：纯 Android 无障碍服务（AccessibilityService）实现点击与截图
2. **全分辨率有效**：所有点击位置来自识别层输出的 bbox 中心，无固定坐标、无分辨率特判
3. **操作可靠**：持续提升目标识别准确率与召回率，并通过识别、决策、操作分层降低异常操作风险

## V1 架构

```
Screenshot
   ↓
Recognition Interface（统一识别接口）
   ├── YOLO Engine        神经图标检测 + OCR 语义（默认引擎，模型不可用自动回退）
   └── Traditional Engine  经典 CV：行结构推导 + 颜色签名 + 证据链（低配设备独立可用，可在设置中切换）
   ↓
Unified DetectionResult（scene + candidates{kind,bbox,conf,evidence[]}）
   ↓
Decision Layer（BotEngine 显式状态机，与识别完全解耦）
   ↓
Operation Layer（无障碍手势，拟人化）
   ↓
UI（Compose Material 3 主题系统"换房子" + 悬浮窗任务面板）
```

**双引擎地位平等**：引擎只负责"看见什么"，决策与操作完全独立——接入第三种引擎不需要改动业务逻辑。默认使用 YOLO 引擎，可在设置中随时切换。

### 识别安全门控（不可变）

1. 识别与决策分离：识别层只负责"看见什么"，买不买、刷不刷由决策状态机独立判断
2. 三重弹窗确认（图标 + 文字 + 精确价格 184,000 / 280,000 严格匹配）
3. 单一最终购买入口（全代码库唯一"确认购买"点击）
4. 截图失败 → 不买不刷新；弹窗残留只点"取消"
5. 金币/天空石预算保护

### 状态机与恢复

`SCAN → SHOP_SCAN → BUYING / REFRESHING → VERIFY`，每步带超时哨兵；弹窗残留 → RECOVER 自动关闭；generation 令牌保证「开始」= 无条件全新会话，杜绝旧线程残留与双线程并发。

## 界面（V1）

- **主题系统"换房子"**：切换主题 = 进入另一套完整设计的界面，各有独立设计语言。内置三套：
  - Steam · 深色卡片（Steam 客户端控制台：深蓝头图、品牌蓝高亮、绿色 PLAY 运行键、大写分区标题）
  - Steam · OLED 纯黑（Steam 结构的纯黑变体：平面色块、无渐变，AMOLED 友好）
  - Blue Archive · 明亮学院（完全不同的布局结构：横幅状态区 + 圆形仪表盘 + 底部操作条 + BA 光环徽章）
- **BA 角色素材位**：把日奈、白子等角色的 PNG 放进 `app/src/main/assets/`（`ba_char_home.png` 替换首页横幅装饰、`ba_char_profile.png` 替换「我的」页顶部，旧 `ba_chibi.png` 兼容回退），未放置时显示 BA 原生光环徽章。
- **悬浮窗彻底重建为"任务面板"**：旧胶囊 + 六格面板被推倒重做。新结构 = 状态胶囊（状态点 / 阶段 / 运行时长 / 引擎徽标 / 错误行）+ 展开任务面板（任务横幅、引擎与截图健康、2×3 统计、滚动事件日志、双行控制区）；随主题切换整套结构（Steam 控制台 / OLED 极简 / BA 学园），横竖屏与刘海安全区自适应。
- **完整中英文**：界面/对话框/按钮/状态/错误/悬浮窗全部双语，切换即时生效、重启保持
- **全屏更新日志**：主题感知配色、舒适行距与版本层级，完整保留从测试期到 V1 的开发历史
- **Shizuku 可选**：安装 Shizuku 时提供一键开启无障碍服务入口，未安装自动隐藏

## 构建

### 依赖（下载后放入 `app/src/main/jni/`）
- [ncnn](https://github.com/Tencent/ncnn/releases)（**simpleomp 自编译版**，官方预编译版自带 libomp 会在部分设备崩溃，见 `jni/CMakeLists.txt`）
- [opencv-mobile](https://github.com/nihui/opencv-mobile/releases)（4.13.0-android）
- PP-OCRv5 模型：来自 [ncnn-android-ppocrv5](https://github.com/nihui/ncnn-android-ppocrv5) 的 `PP_OCRv5_mobile_{det,rec}.ncnn.{param,bin}`
- Shizuku（可选，Maven 自动拉取）：`rikka.shizuku:api:12.1.0` + `rikka.shizuku:provider:12.1.0`（13.x 的 `newProcess` 为私有 API，需用 12.1.0）

### 训练 YOLO 模型（可选）
```
E7SA_Dataset/train_yolo.bat   # RTX 5060，YOLOv8n 300 epochs
```
训练数据在 `E7SA_Dataset/yolo/`（YOLO 格式，含正负样本）。

### 编译
```
Android Studio → Build → Generate Signed APK
或 gradle assembleRelease
```
NDK r29 + CMake 3.22.1 + Kotlin 1.9.24 + Compose Material 3。

### 测试
```
gradle testReleaseUnitTest
```
55 个 JVM 单元测试，覆盖识别层的纯逻辑（关键词匹配、场景判定、行距与容差推导、
购买键定位、画面指纹）与装备评分权重。这些函数直接决定"点哪里"和"买不买"，
一个符号写反就是点错行或漏买 —— 用单测锁住比每次真机回归便宜得多。

### 代码结构
```
bot/      识别与决策（BotEngine / AiBotEngine / Recognition / ClickPlanner / ClickGate / Tuning）
device/   设备 IO（截图 / 点击 / 滑动）
diag/     诊断与回归基准
ui/       主题系统（ThemeCore / ThemeSteam / ThemeBlueArchive）+ 悬浮窗
data/     配置 / 记录 / 日志
```
调参阈值集中在 `bot/Tuning.kt` —— 每个常量都有名字和出处说明，
**改这里等于改行为**，改完必须跑回归台（长按版本号触发）。

详见 `docs/QUALITY_REFACTOR_20260917.md`。

## 免责声明

本项目仅供学习与自动化研究。使用脚本刷商店可能违反游戏服务条款，请自行承担风险。请勿用于商业用途。

## License

ncnn/PP-OCR/YOLOv8 参考实现来自 Tencent ncnn（BSD-3-Clause），保留其版权声明。本项目本体按 BSD-3-Clause 授权。
