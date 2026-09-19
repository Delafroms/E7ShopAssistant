# E7SA 代码质量与安全审查报告

> 审查日期：2026-09-18
> 审查对象：手机（OnePlus PLR110）上已安装的 **E7商店助手**（`com.e7.shop`，versionName 1.0 / versionCode 15，装机时间 2026-09-18 08:17）
> 审查基线：`<repo>` 工作区当前代码（含未提交的 954 行改动）
> 审查方式：4 个独立子代理分模块静态审查 + 主审交叉复核 + 真机/构建/静态分析实证

---

## 0. 先说结论

**手机上装的确实是当前工作区的代码，一行不差。** 全部源文件的最后修改时间都早于 APK 构建时间（08:17），APK 与本地 `E7SA-V1.0-zeromiss.apk` 同一次构建，装机时间完全吻合。所以下面所有结论都直接适用于手机上跑的那个版本。

**综合评分：6.5 / 10**（代码质量 7 / 运行安全与健壮性 6）

这是一个**工程素养明显高于同类个人项目**的代码库：分层清楚、注释解释"为什么"而不是"做了什么"、76 个单测全绿、没有静默 catch、凭据与备份处理得比多数商业 App 还规范、有真机回归台。

但它有**一个会烧付费货币的 P0 缺陷位于默认路径上**，加上一批"注释说已修、代码其实没修"的偏差和相当程度的死代码/重复代码，所以给不到 8 分。**把第 5 节的 P0/P1 修完，这份代码可以到 8.5 分。**

---

## 1. 评分卡

| 维度 | 分数 | 一句话理由 |
|---|---|---|
| 架构与分层 | 8.5 | 识别层/决策层/设备层/UI 层/诊断层五层解耦，依赖注入而非继承，服务类从 1690 行降到 727 行 |
| 可读性与注释 | 9 | 9317 行主代码里有 2094 行注释（22%），且注释普遍写"为什么这么写、踩过什么坑" |
| 正确性与健壮性 | 5 | 默认 AI 管线的预算记账被绕过；点击闸门的返回值 10 处全丢；跨线程共享状态无同步 |
| 安全与隐私 | 6 | 凭据独立存储 + 三处排除备份 + 强制 HTTPS（做得很好）；但静默改写全局无障碍安全开关、截图进云备份、密码走普通输入法 |
| 测试 | 6 | 76 个单测全绿且测的是真逻辑；但两个状态机零测试、ClickGate 零测试、所有 Bitmap 路径全绕开 |
| 工程化 | 5 | lint 6 errors/200 warnings 且构建直接 `-x lint` 绕过；版本控制 2026-09-17 才建立；无 CI |
| UI 代码质量 | 4 | 两个主题文件 38%~44% 逐字复制，269 个字符串里 80 个是死资源，16 个死 drawable |

---

## 2. 实测基本面（都是跑出来的，不是看出来的）

| 项目 | 数值 | 来源 |
|---|---|---|
| 主代码 | 33 个 .kt / 9317 行 | `app/src/main/java` |
| 单测 | 5 个文件 / 679 行 / **76 个用例全绿** | `gradle :app:testReleaseUnitTest` |
| 项目自有 C++ | 7 个文件 / 1248 行（ppocrv5 / yolov8_det / yolov8 / e7ocr / e7yolo + 2 个头文件） | `app/src/main/jni` |
| Kotlin 编译警告 | **12 个**（0 错误，BUILD SUCCESSFUL） | `compileReleaseKotlin --rerun-tasks` |
| Android Lint | **6 errors / 200 warnings**（构建因此用 `-x lint` 绕过） | `:app:lintRelease` |
| 静默 catch | 0 处 | grep `catch (_` / 空 catch 块 |
| TODO/FIXME | 0 处 | grep |
| 硬编码数字（≥100） | 152 处 | grep，多数在主题的 dp/sp 尺寸 |
| 未提交改动 | 13 文件 / +954 −138 行 | `git diff --shortstat` |
| 真机运行数据 | raw_capture 103 张 32MB；关键日志 52KB；单轮会话日志 2.6MB | `adb` 拉取 |

---

## 3. 漏洞与缺陷清单

### P0 — 会烧钱

#### P0-1 AI 引擎的刷新记账被"验证"门控 → 天空石预算闸门永不触发

**位置**：`bot/AiBotEngine.kt:585-599`（记账），`bot/AiBotEngine.kt:189-205`（闸门），对照 `bot/BotEngine.kt:800-808`（已修版本）

确认按钮在 `:585` 已经点下 —— **3 颗天空石此刻已经花掉**；但 `refreshes++` / `skystonesSpent += 3` 在 `:592` 判定 `waitShopLoaded` 返回 `DECIDE_TARGETS` 之后才执行。而 `waitShopLoaded`（`:618-654`）在三种情况下都返回 `undecided`：

1. `:639` 刷新前后画面指纹相同 —— **整屏售罄时这是常态**（刷新前后文本都是"售罄"）
2. `:637-638` 行数 < 5
3. `:624` 18 秒预算耗尽

三种情况都是"钱花了但不记账"。而 `budgetBlocked()` 读的正是 `skystonesSpent` → `maxSkystones` 闸门永远触发不了。再叠加 `:210-212` 注释明写的"不再停机、退避继续观察"，结果是**每轮约 20~25 秒烧 3 颗天空石，整夜无限烧，日志里 `skySpent` 还是 0**。

传统引擎（`BotEngine.kt:800-808`）已经修过这个坑，注释写得非常清楚：

> 确认按钮已经点下，天空石**已经花掉**：必须立刻记账。旧版是「先验证、验证通过才记账」，一旦 waitRefreshed 超时既不计数也不计费，skystonesSpent 永不增长 → 本函数开头的 maxSkystones 闸门永远触发不了 → 网络慢时可以无限刷新、无限烧天空石（付费货币）。

**AI 引擎没有同步这个修复**，而 `data/AppConfig.kt:112` 的 `clickLogic` 默认值就是 `"ai"` —— **默认配置直接命中**。

**修法（一行级）**：把 `:593-596` 的记账移到 `:585` 之后立即执行；`waitShopLoaded` 的结果只决定要不要 `handledClear()`。原则：**COMMIT 绑定 ACTION，验证只决定后续决策，不决定记不记账。**

---

### P1 — 逻辑缺陷 / 会咬人

| 编号 | 问题 | 位置 | 后果 |
|---|---|---|---|
| P1-1 | `guardedTap` 的 Boolean 返回值在 10 个调用点**全部被丢弃** | BotEngine.kt:331/643/717/774/795、AiBotEngine.kt:308/455/552/585/735 | ① `AiBotEngine.kt:408-414` 在"点击后无弹窗、按钮仍可点击"时执行 `handledAdd(t.rowY)` → **把"点击没生效"当成"这行处理完了"，整屏漏买**（传统引擎 `BotEngine.kt:670-692` 已改成返回 FAIL 重试并注明"旧逻辑会白白漏掉一个可买的奖牌"，AI 侧未同步）；② 闸门 DENY（越界/会话失效）时照样记账；③ 白等 12 秒弹窗 |
| P1-2 | 购买计数同样被验证门控 | BotEngine.kt:719-721、AiBotEngine.kt:467-471 | 判据是"弹窗连续 2 帧非 BUY_DLG"，验证超时但商品已买下 → `goldSpent`/`medalsGot` 不涨 → `goldSpendCap` 可被超出 |
| P1-3 | `handledRows` 是跨线程非同步 `HashSet` | ShopAccessibilityService.kt:78（定义）、:249（主线程 clear）、:548/558/560（bot 线程 add/迭代/clear） | `startBot` 只 interrupt 不 join 旧线程 → 新旧 bot 线程与主线程并发 → `ConcurrentModificationException` 被 `BotEngine.kt:157` 的 `catch(Exception)` 吞成 "engine aborted"，会话提前结束。同文件 `eventLog` 是加锁的，说明作者知道这个坑 |
| P1-4 | `didScroll` 每帧分配 17MB + OOM 不可捕 | Recognition.kt:355-356（2×IntArray(regW×regH)，2800×1272 下单个 8.7MB）、AiBotEngine.kt:642（每帧调用） | 单次刷新等待 20 帧 ≈ 350MB 分配压力；`catch(Exception)` 捕不到 `OutOfMemoryError`，全仓库又无 `UncaughtExceptionHandler` → **进程直接被杀** |
| P1-5 | BA 主题「开始」按钮完全静默失效 | ThemeBlueArchive.kt:696-702 vs ThemeSteam.kt:521-531 | BA 不检查 `cfg.riskAccepted`（默认 false）也丢弃返回值，而 `riskAccepted=true` 的**唯一入口在 Steam 主题**（ThemeSteam.kt:526/681）→ 新装用户切到 BA 主题点开始：无弹窗、无 toast、机器人不动 |
| P1-6 | 静默改写**全设备级**无障碍安全开关，且永不恢复 | ShopAccessibilityService.kt:129-147 | 每次 `onServiceConnected` 都把 `accessibility_turn_off_switch` 写 0 —— 这是 Android 对抗无障碍滥用类恶意软件的关键防线，关掉等于**对所有 App 都关掉**；全仓库只有写 0，没有任何路径写回 1。同时清单（AndroidManifest.xml:12-18）只披露了"一键开启无障碍"这一个用途 |
| P1-9 | **模型加载失败无法被检测 → `loaded` 谎报成功**：`yolov8.cpp:33-36/50-53`、`ppocrv5.cpp:184-201` 丢弃 `load_param`/`load_model` 的返回值、`load()` **恒 return 0** | e7yolo.cpp:39-44、e7ocr.cpp:125-131（失败分支因此是**死代码**） | assets 里模型缺失/损坏时，App 照样打印 "detector loaded OK"、`YoloDet.loaded=true`，引擎据此选 AI 管线 → 运行时在未加载的 Net 上 extract，表现为"识别 0 结果"，而日志说模型是好的。**这正是排查"设备上 OCR/YOLO 返回 0 结果"时最需要的那条信息**（他们确实靠 `load=true` 当冒烟信号用过） |
| P1-7 | 组合期（主线程）整图解码，无采样 | ThemeSteam.kt:351-352、:698-700、ThemeBlueArchive.kt:258-263；helper 在 MainActivity.kt:494-521 | `remember{}` 在组合期执行 = 主线程；`loadBgBitmap/loadLogoBitmap` 无 `inJustDecodeBounds`/`inSampleSize`。相册 48MP 图 ≈190MB ARGB_8888 → **必然 OOM**。对照 `score/EquipmentScoreActivity.kt:73-78` 就正确做了采样 |
| P1-8 | **native 层的 use-after-free**：`PpOcr.load()` 无条件 `delete g_ocr` 再 new | e7ocr.cpp:111-117（load）、:181（`g_ocr->detect_and_recognize`）、EquipmentScoreActivity.kt:38/87（每次进评分页都调 load）、ShopAccessibilityService.kt:96（服务连接时后台线程 load） | **机器人运行中打开「装备评分」页 → load() 释放掉机器人线程正在使用的 `g_ocr` → SIGSEGV**（native 崩溃，Kotlin 的 try/catch 捕不到，进程直接死）。同类风险：服务刚连接、模型还在后台加载时立刻点开始，`g_ocr` 已非空但未就绪 → 半初始化对象上跑推理。`YoloDet` 有 `loaded` 标志可挡，`PpOcr` **没有** |

---

### P2 — 质量 / 可维护性 / 中等风险

**功能与并发**
- `AiBotEngine` 6 处绕过 `Tuning` 写死魔数（`:503/716/469/527/514/638`），`ShopStateTracker.kt:98` 的 `MIN_ROW_TOL=40f` 是唯一裸像素容差
- `BotState` 非 `@Volatile` 被 3 个线程读写（ShopAccessibilityService.kt:64/205-214/637-642），`MainActivity.kt:202` 的 2 秒轮询不经 Handler，可能拿到撕裂快照
- `FloatyController` 的日志去重游标跨线程无同步（`:129-133` 写于主线程 `:150-157` 与 bot 线程 `:161-196`）；`sync()` 每次 publish 都做 `Settings.canDrawOverlays` binder IPC
- `RailFloatySkin.destroy()` 只清一半引用，`statLabels` 只 add 不 clear
- 正常停止被记成异常：`Humanizer.kt:36/45/56/63` 直接 `Thread.sleep` 不吞中断 → `InterruptedException` 冒泡成 `E7SA.Crash` + `err=异常`，污染"这晚有没有出错"的判断（`DeviceIo.kt:143-150` 就刻意吞了中断）
- `Recognition.kt:36-48` 的 native 失败计数是死代码（`PpOcr`/`YoloDet` 内部已把 Throwable 吞成 emptyList，外层 catch 永不进入）
- 无全局 `UncaughtExceptionHandler`：引擎循环之外的崩溃（悬浮窗主线程回调、服务生命周期）只进 logcat，不落文件

**安全与隐私**
- **Shizuku 通道是死链（已三重确证）**：① 官方文档要求在 App 清单里声明 `ShizukuProvider`，而 merged manifest 里没有；② 真机 `dumpsys package com.e7.shop` 显示该包**唯一注册的 Provider 是 `androidx.startup.InitializationProvider`**；③ R8 `usage.txt:52646-52647` 显示 `rikka.shizuku.ShizukuProvider` 已被当无用代码删除。`Shizuku.pingBinder()` 恒为 false → 装了 Shizuku（手机上确实装了 `moe.shizuku.privileged.api`）也永远用不上，相关依赖/权限是纯死重
- 全屏游戏截图 `debug_last.png` 落在内部 `filesDir`（DeviceIo.kt:52-57），属 Auto Backup 默认集合，而 `allowBackup=true`、规则只排除了凭据文件 → 截图会随云备份/换机迁移上传，还有打爆 25MB 配额导致整包备份失败的风险
- WebDAV 密码明文存 SharedPreferences（AppConfig.kt:36-37，已排除备份，可接受）；但密码输入框**没设 `keyboardType=Password`**（ThemeSteam.kt:1226-1239），只有视觉遮挡 → 云输入法会学习
- WebDAV **账号邮箱** `wdAccount` 留在 `e7_config`（AppConfig.kt:291-293），而 `e7_config` **是**被备份的 → 邮箱随云备份/D2D 外流（密码排除了、账号没排除）
- WebDAV 下载无体积上限（WebDavSync.kt:112）、导入无二次确认直接覆盖本地记录（RecordStore.kt:162-188，无范围校验，可导入负数）
- 外部私有目录可涨到 GB 级无总量上限（RunLog 30×15MB + critical 4MB×2 + raw_capture 30 张 + benchmark）
- `POST_NOTIFICATIONS` 在清单里声明，但代码中**零处申请**（真机 `granted=false`）→ Android 13+ 上保活通知在通知栏不可见，用户看不到"机器人在跑"
- `accessibility_service_config.xml` 未设 `packageNames`，服务订阅所有应用的 windowStateChanged（`onAccessibilityEvent` 是空实现）——**注意：好消息是从未声明 `canRetrieveWindowContent`，全仓库 0 处 `rootInActiveWindow`/`AccessibilityNodeInfo`，确实不读控件树，能力是最小化的**
- **release APK 用 Android 调试密钥库签名**（apksigner 验证：CN=Android Debug, O=Android, C=US），私钥是本机共用的调试密钥库（口令为公开的默认值 `android`）。分发给群友的场景下，任何人拿到该文件即可签出**被系统当作合法升级接受**的包。好消息：keystore 已在 `.gitignore`、仓库里没有它，也没进备份
- 无障碍服务未重写 `onUnbind`，`isEnabled()` 可能失真

**native / JNI 层（1248 行，主审逐文件读过）**
- JNI 局部引用管理是**对的**：`e7yolo.cpp:103-106` 每个字符串都 `DeleteLocalRef`，无循环泄漏；无 `GetStringUTFChars`/`ReleaseStringUTFChars` 配对问题；无 C++ 异常显式抛出
- stride-safe 拷贝（`e7ocr.cpp:162-173`、`e7yolo.cpp:64-73`）是**踩过坑后写对的**，注释把"直接 wrap 会shear 图像导致 0 结果"讲清楚了
- `g_ocr` / `g_yolo` 是**裸全局指针、无任何同步**（见 P1-8）；`PpOcr` 连 `loaded` 标志都没有（`YoloDet` 有）
- `std::vector<unsigned char> tight(h*w*4)` 分配失败会抛 `std::bad_alloc`，**穿过 JNI 边界 = std::terminate**（理论；超大 Bitmap 时可触发）。`AndroidBitmap_lockPixels` 之后若抛异常也不会 unlock
- `e7ocr.cpp:85-97` `JNI_OnLoad` 里的 `setenv("OMP_NUM_THREADS","1")` 等是给**真 libomp** 防 SIGABRT 用的，而 CMakeLists 已明确把 libomp 从链接线剔除（改用 ncnn 的 simpleomp）→ 这三行现在是**失效的死代码**，注释与构建现状不符（文档漂移，非 bug）
- `CMakeLists.txt:72` 的 `-Wl,--allow-multiple-definition` 是掩盖 simpleomp 与 opencv 符号冲突的权宜手段，能跑但会掩盖未来真正的重复定义
- **`YOLOv8` 的 `det_target_size` 无默认值**（`yolov8.h:54` 裸 `int`），且类**没有构造函数** —— 唯一的初始化写在**析构函数**里：`yolov8.cpp:17-20` `YOLOv8::~YOLOv8(){ det_target_size = 320; }`（析构里给成员赋值，显然是笔误，对照 `ppocrv5.cpp:127-130` 的构造函数写法）。目前靠 `e7yolo.cpp:46` 紧跟 load 调 `set_det_target_size` 才没炸，一旦调用顺序变化就是**不确定值进 letterbox 运算**
- `ppocrv5.cpp:63` `const float target_width = rh * target_height / rw;` **没有 rw==0 保护**：`minAreaRect` 对近似共线的轮廓（≥3 点、`contour.size() <= 2` 挡不住）可能给出宽度 0，此时 target_width = inf → `cv::warpAffine` 收到非法尺寸 → OpenCV 抛异常，**穿透 JNI 边界 = std::terminate**（理论，需构造图）
- `ppocrv5.cpp:290-304` 的逐像素诊断扫描**留在生产热路径上**：每次 detect 对整张 pred（640×640≈41 万像素）用 `pred.at<uchar>(i)`（每次带行列换算）扫一遍，并打两条 logcat —— 调试代码未降级为开关
- `ppocrv5.cpp:385` `recognize()` 内部调 `cv::setNumThreads(1)`，而 `recognize` 是在 `#pragma omp parallel for` 里被并行调用的 → 在并行区内写全局线程数设置（JNI_OnLoad 早已设过），语义可疑且无意义
- 正面：共享 `ppocrv5_rec` Net 上多线程 `create_extractor()` 是 ncnn 支持的用法；每个线程只写各自的 `objects[i]`，无数据竞争；`#pragma omp parallel for` 用默认静态调度（与 simpleomp 兼容），注释把 `schedule(dynamic)` 的链接坑写清楚了
- 无 `JNI_OnUnload`、模型常驻不释放（App 生命周期内可接受）

**工程化与 UI**
- lint 6 errors / 200 warnings，构建用 `-x lint` 整体绕过（其中 `ProtectedPermissions` 是设计使然，但 `MissingTranslation` 和 109 个 `UnusedResources` 是真的）
- 269 个字符串里 **80 个零引用**；16 个死 drawable；`Theme.E7Shop.OLED`、`res/color/bottom_nav_item.xml` 整文件死；金色散落三个不同值
- `RunLog.enabled` 是死字段（注释写"玩家可在设置里关掉"，但无人赋值）
- 两主题文件 LCS 实测 **497 行逐字相同 = BA 的 44.1%**（剔除 102 行 import 后仍 38.5%），StProfile/BaProfile 63%；复制已造成真实回归（BA 漏 risk 闸门、漏 `keyboardOptions`）
- i18n 跨模块泄漏三处：`AiBotEngine` 9 条中文 reason 直接画到悬浮窗错误行、`RecordStore.kt:206-210` 硬编码"亿/万"、`Changelog.kt` 整篇中文
- `RailFloatySkin.kt:363-367` 的 `take(2)`：英文 "Checking shop..." → "**Ch**"，中文 "发现目标！购买中…" → "**发现**"
- `proguard-rules.pro:13` `-keep class okhttp3.** { *; }` 让 R8 对整库失效
- `ScoreEngine.kt:87-88` 用 `lowercase()` 后的串算索引、却切**原串** → 文本含 'İ' 等大小写展开后长度变化的字符时 `substring` 越界（理论，被 `EquipmentScoreActivity.kt:63-68` 的 catch 吞成"OCR 失败"）
- `ShopAccessibilityService.kt:243` 在**主线程**调 `runLog.sessionStart` → `RunLog.kt:87/111-120` 做 listFiles+delete（主线程 I/O）
- `accessibility_turn_off_skip_package` 用 `:` 拼接（ShopAccessibilityService.kt:140），**与 ColorOS 实际分隔符格式是否一致未验证**
- WebView 无必要开 JS（MainActivity.kt:328，内容本地且全文转义，今天无注入点，但无收益地扩大攻击面）
- 版本控制 2026-09-17 才建立（3 个提交），之前两周无历史；无 CI、无 lint 基线

---

## 4. 值得肯定的地方（这些是真做对了）

1. **凭据处理比多数商业 App 规范**：密码拆独立 `e7_secrets.xml`，`backup_rules.xml` 与 `data_extraction_rules.xml` 三处排除，旧值一次性迁移并清除；凭据只进 Authorization 头，零落日志。
2. **强制 HTTPS**（WebDavSync.kt:38-42）+ targetSdk 36 无 `usesCleartextTraffic` → 平台默认禁明文，双保险。
3. **组件暴露面干净**：只有 SplashActivity 是 exported（且完全忽略外部 Intent）；无 `registerReceiver`/`PendingIntent`/`sendBroadcast`；无障碍能力没有任何对外导出路径。
4. **所有点击都经同一道闸门**：全库 10 处点击全部走 `guardedTap`，无旁路；无固定/录制坐标。
5. **识别失败 fail-closed**：截图失败返回 null，调用方一律不买不刷新。
6. **行容差由行距推导**且恒小于行距一半（Recognition.kt:209-247），单测锁死了这条不变量。
7. **配置能力矩阵审计是真做了**：我独立把 36 个 `AppConfig` 字段逐个 grep 外部读者，**全部有运行时读取点，没有一个假开关**。
8. **静默 catch 清零、无 TODO、无 GlobalScope、无 printStackTrace**。
9. **回归台 + 真机验证闭环**：双引擎基准（召回 100%/误检 3）、每次改动构建+签名+装机。
10. **文档记录决策**：6 份 docs 记录了重构、配置审计、BUG 根因，注释里写的是"为什么"。

---

## 5. 改进建议（按优先级）

### 第 0 档：今天就该修（各 ≤ 30 分钟）

1. **P0-1 记账前移**：`AiBotEngine.kt` 把 `s().refreshes++ / s().skystonesSpent += 3 / host.counters(s())` 移到 `:585` 的 `guardedTap` 之后立即执行，`waitShopLoaded` 只决定 `handledClear()`。**验收**：故意在刷新时把网络断开 → 日志里 `skySpent` 仍应 +3，且达到 `maxSkystones` 后停下并报 `err_sky_budget`。
2. **P1-1 接住 `guardedTap` 返回值**：返回 false（DENY）时不计账、不 `handledAdd`；并删掉 `AiBotEngine.kt:408-414` 里"按钮仍可点击 → handledAdd"这段，改成与 `BotEngine.kt:670-692` 一致的重试语义。**验收**：ColorOS 模态框吃点击的场景下不再整屏漏买。
3. **P1-3 `handledRows` 换成线程安全集合**（`ConcurrentHashMap.newKeySet()` 或加锁），并把 `clear()` 移进 bot 线程开头。**验收**：连续 start/stop 20 次不出现 "engine aborted"。
4. **P1-6 停止全局改写 `accessibility_turn_off_switch`**：只保留 `skip_package` 豁免（影响面小得多），并做成用户可见开关；若坚持要写，至少在授权页披露并支持恢复。**这条是伦理与信任问题，不只是技术问题。**
5. **P1-4 `didScroll` 复用缓冲**：把两个 `IntArray` 提为成员变量（按尺寸变化时重建），`catch` 改 `Throwable`；同时加一个 `Thread.setDefaultUncaughtExceptionHandler` 把崩溃写进 `runLog.critical`。**验收**：挂机 8 小时无进程重启、日志里有崩溃记录（如果有）。

### 第 0 档补一条

5b. **P1-8 给 native 模型指针加锁 + 幂等**：`nativeLoad` 里加一个 `std::mutex`（或 `std::atomic<PPOCRv5*>` + 引用计数），并让 `nativeLoad` 在已加载且参数相同时直接返回 true；Kotlin 侧给 `PpOcr` 补一个 `@Volatile var loaded`（照抄 `YoloDet` 的写法），`recognize()` 在 `!loaded` 时直接返回 emptyList。**验收**：机器人运行中反复进出「装备评分」页 20 次，不出现 native 崩溃。**这是本次唯一一条会导致进程直接死掉的缺陷，建议优先于其他 P1。**

### 第 0 档补第二条

5c. **P1-9 让 `load()` 真的能失败**：`yolov8.cpp` / `ppocrv5.cpp` 的 `load_param`/`load_model` 返回值改成 `int ret = ...; if (ret != 0) return ret;`，让 `e7yolo.cpp:39` / `e7ocr.cpp:125` 的失败分支不再是死代码。**验收**：把 assets 里的 `.bin` 改个名字，App 应打印 `load=false` 并回退传统引擎，而不是"loaded OK + 识别 0 结果"。顺手把 `yolov8.cpp:17-20` 析构里的 `det_target_size = 320` 挪到构造函数（并给成员加默认值）。

### 第 1 档：本周（各 ≤ 2 小时）

6. **P1-7 图片解码移出组合期**：`produceState + Dispatchers.IO` + `inJustDecodeBounds`/`inSampleSize` + `onDispose { recycle() }`；`remember(cfg.bgImage)` 补 key。参考 `EquipmentScoreActivity.kt:73-78` 的现成写法。
7. **P1-5 BA 主题补风险闸门**：把 Steam 的 `riskAccepted` 判定 + 返回值 toast 抽成共享 composable，两个主题都调（顺手解决重复代码问题）。
8. **P2 购买计数解耦**：`BotEngine.kt:719-721` / `AiBotEngine.kt:467-471` 改成"点下确认即计数"，与刷新记账同一原则。
9. **Shizuku 通道二选一**：要么在清单里加 `ShizukuProvider`（`android:authorities="${applicationId}.shizuku"`、`android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`、`exported="true"`），要么删掉通道二 + 两个依赖 + `moe.shizuku.manager.permission.API_V23` 权限。**现在这样是"看得见、点了没用"的死开关。**
10. **备份规则补两行**：排除 `domain="file" path="debug_last.png"` 与 `domain="external"`，或把调试帧/日志改放 `noBackupFilesDir`/`cacheDir`。
11. **补上最该有的单测**：`Host` 与 `RecognitionEngine` 都是接口，十行 Fake 就能测 —— ① 记账不变量（刷新/购买后计数必须增长）② `ClickGate` 四条 DENY 断言 ③ `mergeCandidates`/`priceMatches` 这两个防误买的纯函数。**这 8 个用例能把本次全部 P0/P1 回归面盖住。**

### 第 2 档：V1.1 清理轮

12. **消重**：把两个主题的公共骨架抽出来（`E7Theme` 契约里补 shapes/accent 等设计参数，让"骨架只写一遍、主题只给配色"），目标把 44% 的逐字重复降到 < 10%。
13. **死代码/死资源清理**：80 个死字符串、16 个死 drawable、死 style、`RunLog.enabled`、`BotEngine.Phase.BUYING` 死分支、构造参数 `generation`、`Humanizer.scale/randFloat`。一次清完，别再分批。
14. **恢复静态检查门禁**：`lint { baseline = file("lint-baseline.xml") }` 把 6 个 errors 里的设计使然项（`ProtectedPermissions`）固定进基线，剩下的真问题修掉；再把 12 个 Kotlin 警告清到 0。**别再用 `-x lint` 整体跳过。**
15. **i18n 收口**：`AiBotEngine` 9 条 reason 走 `R.string`、`fmtNum` 的亿/万走资源或 `CompactNumberFormat`、`Changelog` 出中英双版、`RailFloatySkin.shortStage` 改成专门的短文案资源。
16. **通知权限**：启动时申请 `POST_NOTIFICATIONS`（或在清单里删掉该权限，接受通知不可见）。
17. **外部目录加总量上限**（如 100MB）+ LRU 清理，并在设置页给一个"清理诊断文件"入口。
18. **WebDAV 加固**：下载限制体积（如 1MB）、导入前二次确认 + schema/范围校验、密码框补 `keyboardType=Password`、把 `wdAccount` 一并移入 secrets 文件。

18b. **换掉 release 签名密钥**：生成专用 release keystore（别用 debug.keystore）并单独备份保管。现在的签名意味着"谁拿到那个文件，谁就能给你所有用户推一个系统认可的升级包"。

### 长期

19. 把"注释声称已修"变成"测试锁住已修" —— 本次多个 P1 都是"注释写了、另一条管线没同步"（AI vs 传统）。**两条管线要么共享同一段会话/记账代码，要么用同一组测试同时约束。**
20. 建立 CI（哪怕是本机脚本）：每次提交跑 `testReleaseUnitTest` + `lint` + 回归台断言，别让人肉记住这些。

---

## 6. 审查方法与证据来源

- **3 个独立子代理**返回完整分模块报告（bot 核心 / UI+资源 / 配置·数据·网络·安全），每条结论要求 `文件:行号` 证据，禁止无证据推断；**native/JNI 层由主审亲自读过**：`e7ocr.cpp`/`e7yolo.cpp`/`ppocrv5.cpp`/`yolov8.cpp`/`ppocrv5.h`/`yolov8.h`/`CMakeLists.txt` **全文**，`yolov8_det.cpp` 读了文件头（数据布局说明）、load 路径与关键行（NMS/后处理正文未逐行读）。第 4 个子代理超时未返回、被中止，其结论**一条未采信**
- **主审交叉复核**：对子代理报的每条 P0/P1 都回到源码逐行核对（P0-1、P1-1、P1-3、P1-5、P1-6、P1-7、Shizuku 死链均已一手确认）
- **真机实证**：`adb` 查包信息/权限/Provider 注册、拉取运行日志与关键日志、核对源码 mtime 与 APK 构建时间
- **构建实证**：`gradle :app:lintRelease`（6 errors/200 warnings）、`compileReleaseKotlin --rerun-tasks`（12 warnings）、`testReleaseUnitTest --rerun-tasks`（76/76 通过）
- **第三方文档核对**：Shizuku 官方 README 关于 `ShizukuProvider` 必须在 App 清单中声明的原文要求

## 7. 未验证事项（诚实标注）

- 所有渲染/交互类结论（edge-to-edge 遮挡、无限动画的实际重组粒度、Rail 拖动结束是否误触展开、BA 开始按钮的实际点击表现）均为静态推断，**未在真机上逐条复现**
- P1-4 的 OOM 路径未实际触发（需要长时间挂机 + 特定分辨率）；P1-8 的 native use-after-free 也未在真机复现（需要在机器人运行时进「装备评分」页）
- 外部私有目录是否进 Auto Backup 默认集合依据官方文档，未真机验证
- 已严格核对：
  
  ```
  晚于 APK（08:17）的 app/src/main 文件数 = 0
  ```
  
  即手机上的包与本次审查的代码完全一致；若之后又改过代码，需重新对照

---

*本报告为只读审查，未修改任何项目文件。*
