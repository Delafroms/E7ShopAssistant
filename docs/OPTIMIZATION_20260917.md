# E7SA 优化记录（2026-09-17 · 第二轮）

承接 `QUALITY_REFACTOR_20260917.md`（第一轮：拆分上帝类、集中常量、补 55 个单测）。

本轮目标不是继续拆文件，而是补齐**第一轮没覆盖的三类缺口**：
版本控制、运行时不一致、可验证性。原则同上一轮 —— **不改变任何真机行为**。

---

## 一、逐项改动

### 1. 建立版本控制（此前完全没有）

**问题**：8000+ 行代码、146MB 第三方依赖、多轮重构，却没有任何 `.git`。
回退全靠 `_backup_src_20260917_prequality` 这类手动目录 —— 无法定位"哪次改动引入了回归"。

**做法**：
- `git init`（main 分支），首次提交 `51c92eb` 作为**重构后基线**
- 新增 `.gitignore`：排除第三方预编译依赖（`ncnn-arm64-v8a/`、`ncnn-armeabi-v7a/`、
  `opencv-mobile-*/`，共约 146MB）、构建产物、调试截图、手动备份目录
- 仓库体积 43.4MB（含 assets 模型 22MB 与回归基准图 14.3MB，二者是运行/回归必需，故保留）

**影响**：从"无法追溯"变为"可回滚、可定位回归、可并行实验"。

### 2. 修复"设置显示 8、实际生效 4"（真·不一致）

**问题**：点击偏移的上限在三个地方各写一份，且互不相同：

| 位置 | 值 | 作用 |
|---|---|---|
| `AppConfig.offsetPx` 默认值 | **8** | 首次安装的默认值 |
| `SettingsSchema` setter | `coerceIn(0, 4)` | UI 可设范围 |
| `Humanizer.offsetPoint` | `coerceIn(0, Tuning.TAP_OFFSET_MAX_PX)` | 运行时夹取 |

后果：老用户（SharedPreferences 里存着 8）打开设置页**看到 8**，实际却按 4 执行。
`Tuning.kt` 的注释还写着"设置里允许 0~20"，与代码里的 0~4 直接矛盾。

**做法**：
- 上限唯一定义移到 `AppConfig.TAP_OFFSET_MAX_PX`（data 层）——
  因为 `ui` 与 `bot` 两个包**都必须与它一致，而 ui/data 都不依赖 bot**，所以不能放在 `bot/Tuning`
- `Tuning.TAP_OFFSET_MAX_PX` 改为引用它（保留原符号，避免改动调用方）
- `SettingsSchema` 不再写死字面量 `4`
- `AppConfig.offsetPx` 的 **getter 与 setter 都夹取** → 显示值与生效值恒等

**影响**：UI 显示与实际行为一致；上限改一处即全链路生效。

### 3. 消除唯一一处完全静默的 catch

**问题**：`AiBotEngine.engine_hasIconFast` 里 `catch (e: Throwable) { }` —— 全项目唯一
一个既无日志也无返回值的空 catch。它每帧调用，一旦持续失败，现象是"画面一直不稳定"，
最终卡在 WAIT 阶段，而日志里查不到任何原因。

**做法**：保留 `Throwable` 捕获范围（与项目内其他 native 调用一致 —— 它们要覆盖
`UnsatisfiedLinkError` 这类 Error），**补上日志**。

**影响**：零行为变化；故障时日志直接给出异常类型与消息。

### 4. 补 11 个单测（55 → 66）

**问题**：第一轮 55 个单测集中在识别逻辑、评分、诊断；观测层
`ShopStateTracker` 一个测试都没有。而它是**群友报障时唯一的证据来源** ——
玩家说"买完不下滑 / 卡住不动 / 反复点同一行"时，全靠它的状态变迁去区分
「识别没看见」与「看见了但决策没动」。

**做法**：新增 `ShopStateTrackerTest`（11 个用例），重点覆盖三处**写错就会误导排查**的地方：

- **容差判定**：行位置抖动（20px < tol）不得被误报成"旧行消失 + 新行出现"
- **`MIN_ROW_TOL` 下限**：低分辨率下 tol 可能只有几像素，不设下限会把 30px 抖动读成换行
- **候选顺序无关性**：识别层输出顺序不保证稳定，不能因此判成"内容变了"而打断 staticStreak

另外覆盖：售罄翻转（flip）、远处售罄文本不污染本行、reset 语义、空候选不崩。

### 5. 清理编译警告（16 → 8）

| 警告 | 处理 |
|---|---|
| `AiBotEngine` 3 处多余 `!!` | 提取局部变量，语义不变 |
| `SplashActivity` 未使用变量 `anim` | 删除变量名（对象仍由 `apply{start()}` 自持） |
| 3 处 deprecated 图标（`Icons.Filled.Article/List`） | 迁移到 `Icons.AutoMirrored.*` |
| `MainActivity` 未使用的 `Article` import | 删除 |

**保留未改的 8 条**（均为 deprecated API 迁移，涉及真机行为，需回归验证）：
`systemUiVisibility` 6 处（沉浸式全屏）、Shizuku `newProcess`、`SCREEN_DIM_WAKE_LOCK`。

---

## 二、验证

| 项目 | 结果 |
|---|---|
| 单元测试 | **66 个全绿**（原 55 + 新增 11），0 失败 |
| `assembleRelease` | BUILD SUCCESSFUL |
| 编译警告 | 16 → **8** |
| APK 签名 | SHA-256 `ff396fba…120534` —— 与线上版本**完全一致**，可覆盖安装 |
| APK 体积 | 47.8MB（与优化前一致） |

**未做真机回归**：本轮优化期间手机 USB 已断开，回归台（长按版本号 → `benchmark_report.json`）
无法执行。以上全部为 JVM 与构建层验证。**真机回归待补**。

---

## 三、发现但**刻意未改**的问题

### 滑动几何两处定义，跨度相差 2.7 倍

`slot6Check` 与 `revealSlot6` 目标相同（揭示第 6 格）、判据相同（连续 2 次"没动"才算到底），
但滑动几何取自不同来源：

| 函数 | 参数来源 | 跨度 |
|---|---|---|
| `slot6Check` | `Tuning.SWIPE_LOW_Y`(0.86) → `SWIPE_HIGH_Y`(0.14) | 0.72h |
| `revealSlot6` | `cfg.swipeBottomY`(0.46) → `cfg.swipeTopY`(0.19) | 0.27h |

未擅自统一的理由：滑动幅度直接决定真机手势效果，改动必须先在真机回归台上验证
"第 6 格确实露出且不漏买"，否则就是在没有证据的情况下替换一个已验证行为。
已在 `revealSlot6` 处加注释标记。

### 配置字段审计结论（全部 35 个字段）

用脚本逐个核对"UI 入口 ↔ 业务读者"：

- **32 个**：有 UI 入口且有真实读者 ✅
- **3 个**（`swipeCenterX` / `swipeTopY` / `swipeBottomY`）：无 UI 入口，但**属刻意设计** ——
  源码注释已写明"滑动几何是跨分辨率自适应参数，暴露给用户只会制造误配置"

**未发现新的假开关**（有 UI 无读者）或死字段。

---

## 四、本轮未做的项及原因

| 项 | 原因 |
|---|---|
| 拆 `ThemeSteam.kt`(1194) / `ThemeBlueArchive.kt`(1089) / `FloatyController.kt`(970) | 第一轮文档已判定"收益递减"；纯 UI 移动无法用单测验证，且当前无真机条件 |
| 抽 `SessionRunner` 合并两引擎重复骨架 | 第一轮文档明确警告"两条管线刻意保持独立，抽取需谨慎不要引入耦合"。无真机回归前不动 |
| 迁移剩余 8 条 deprecated API | 涉及沉浸式全屏、Shizuku、WakeLock 的真机行为，需回归验证 |
| 更换 release 签名（当前为 debug 证书） | 换签名会导致**无法覆盖安装**，老用户必须卸载重装、数据丢失。属产品决策，须由作者定夺 |
