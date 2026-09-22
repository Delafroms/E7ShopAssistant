# E7SA 修复记录汇总（逐轮）

> **文档性质**：原始技术记录。保留每一轮修复的**根因、修法要点、涉及函数与阈值、验证状态**，供后续排查同类问题时对照。
>
> 面向使用者的**摘要**见 [../README.zh-CN.md](../README.zh-CN.md) 的「修复内容与版本历史」；缺陷台账见 [TEST_REPORT.md](TEST_REPORT.md)。
>
> 本文档由 8 份逐轮修复记录（FIXES_20260918 / FIXES_20260919 / round2~5 / 点错行活锁 / UI 去重）整理合并而成。

---

# E7SA 历史修复记录提炼（供主 README 整合）

## 1｜FIXES_20260918（审查第0/1档）· 真钱记账 + 进程稳定性
根因：记账被"验证成功"门控——动作已发生，验证慢/失败就不记账 → 无限烧天空石、超金币上限。
要点：①定死 **ACTION→COMMIT**：`confirmRefresh()` 的 `refreshes++/skystonesSpent+=3` 移到 `guardedTap` 后立即执行，`waitShopLoaded` 只决定 `handledClear()`；`countPurchase()` 同理；`guardedTap=false` 不记账。②漏买：`verifyDialog()` 删掉 CLICKABLE 分支的 `handledAdd(rowY)`——行仍可买=点击没生效，不标记。③native UAF：`e7ocr/e7yolo.cpp` 加载加 `std::mutex`+幂等（绝不 delete）、加载完成才发布指针；触发=运行中开装备评分页→SIGSEGV（Kotlin catch 不到）。
其他：采样解码防 OOM（目标宽 1080）；`didScroll` 的 2×8.7MB IntArray 改 ThreadLocal；`handledRows`→`ConcurrentHashMap.newKeySet()`；delay 限 50ms~30s；删 `accessibility_turn_off_switch=0` 全局写入、改 `acc_exempt` 开关授权；backup 排除 `debug_last.png`；补 ShizukuProvider 声明+proguard keep。
影响：双引擎 / JNI / 解码 / 无障碍 / 备份。验证：88 单测全绿；**真机✗**（交付时无设备）。

## 2｜FIXES_20260919 · 全量审计
根因：native 失败被吞成 emptyList 使调用方 try/catch 成死代码；跨线程共享可变对象（BotState 快照、日志游标）。
要点：①`takeFailure()` 让失败可上报；BotState 13 字段 `@field:Volatile`+发布拷贝；`ScoreEngine` 改用**原串** ignoreCase 查找（旧版在 lowercase 串取下标切原串，'İ' 静默吃掉一位数字）。②安全：`wdAccount` 移入 `e7_secrets`（带迁移）、密码框补 `KeyboardOptions(Password)`、WebDAV 下载加 2MB 上限、导入计数/金币/时长越界**整体拒绝**、Android 13+ 申请 `POST_NOTIFICATIONS`、无障碍 `packageNames` 限定 `com.stove.epic7.google`。③工程化：lint 基线门禁 `abortOnError=true`（冻结 112 条，不再 `-x lint`）、okhttp keep 收窄为 6 类型、清 80 字符串+17 drawable。
影响：识别 / 评分 / 云同步 / 无障碍 / 构建。验证：89 单测+lint+**真机装机冒烟✓**。

**运维要求（收窄 okhttp keep 之后）**：需人工回归一次 —— 在设置页点一次「测试连接」+「上传 / 下载」；若失败，把 keep 规则恢复成 okhttp3 全包即可（原规则更宽，恢复无副作用）。

## 3｜round2 · 正式签名 + 魔数收敛
根因：debug 签名线与正式线无法互覆盖，多轮共用 versionCode 15 无法分辨版本。
要点：①新建 `keystore/e7sa-release.jks`（RSA4096/10000 天，别名 e7sa），`keystore.properties` 驱动 gradle 直接出签名 APK——**密钥丢失=再也无法给已装用户发更新**。②versionCode 15→16，此后每轮递增。③25 处 sleep/randInt 魔数收进 Tuning 命名常量（`POLL_TICK_MS`/`DIALOG_POLL_MIN/MAX_MS`/`RESHOT_RETRY_MS`/`SETTLE_AFTER_RECYCLE_MS`），**数值一个没改**。
影响：发布流程 / 两管线时序。验证：89 单测+lint；真机卸载重装✓、权限重建✓、冒烟✓。

## 4｜round3 · 真钱语义收口 + 主题行为去重
根因：金币/天空石闸门在 3 处各写一遍（`doBuy`/`doRefresh`/`budgetBlocked`）——双管线同步陷阱，且管的是真钱。
要点：①抽 `bot/BudgetGate.kt` 纯函数两管线共用；`BudgetGateTest` 10 例钉死边界：`cap<=0=不限制`、`spent==cap` 即停（>= 语义）、超上限最多一件。②`TuningTimingTest` 3 例钉住 25 个常量确切值（手滑改值即红）。③主题只抽行为：`ui/WebDavActions.kt` 收口上传/下载导入/测试连接（两段已分叉：Steam 嵌套 if vs BA 合并条件式），布局零改动。
影响：预算语义 / 时序 / 主题。验证：102 单测+lint；同签名覆盖升级✓；**坑：App 更新后系统无障碍启用状态会被清掉，需重开**。

## 5｜round4 · Robolectric + BotEngine FSM
根因：P0 的错在**顺序**（钱花了没记账），纯函数单测永远抓不到，必须整机推 FSM。
要点：①引入 Robolectric（不需 AVD，JVM 影子 Android，android-all≈100MB）换来真 Bitmap+真 Context。②`BotEngineFsmTest`：钱路径真买到 5 书签（断言 `goldSpent=184000`/`bookmarksGot=5`/闸门下轮触发）、漏买回归、零感知 fail-closed 不点不滑。③钉住三条真实规则：`dialogBuy` 要求先见「取消」且购买键在其右；`dialogConfirmed` 三重验证（商品名+图标旁证+价格唯一匹配，恰一条 6 位数）；点击没生效会反复重试同一行。
影响：仅测试代码，**生产零改动**（APK 同 vc16b）。验证：107 单测；真机✗。构建坑：android-all 走测试 JVM 系统属性、不继承 Gradle 仓库配置，需设 `robolectric.dependency.repo.url` 阿里云镜像 + `@Config(sdk=[34])`。

## 6｜round5 · 红队用例 + AI 管线 FSM
根因：红队编排器用"历史上点过行内购买"的粘滞标志决定画面，与引擎 FSM 不同构 → 画面永久是弹窗 → 全部误报。
要点：①改为按「弹窗**此刻**是否还开着」驱动。②修正两处断言错误：handled 会被刷新清空（要查历史）、超时保守计数只允许写 UNCONFIRMED、绝不写 PURCHASE COMMIT。③AI 管线可测化（行为不变）：`AiBotEngine(host, generation, vision=YoloEngine())` 注入视觉引擎、`YoloDet.loaded` 改 `internal set`；三例覆盖"模型未加载即报错退出、绝不降级传统点击"、"零感知不点不滑"、"停止请求立即退出"。
影响：两管线均有 FSM 约束。验证：115/115、0 跳过；真机✗。关键证据：价格歧义→VERIFY FAIL x3 后暂时放弃该行（封顶）。实现细节：新增 BuyResult.VERIFY_FAIL 与 noteVerifyFailure()，常量 Tuning.ROW_VERIFY_MAX_ATTEMPTS = 3；达上限后 handledAdd(rowY) 暂时放弃该行（刷新/滑动清零，非永久跳过）；提交 8b1d34c。边界：三类确定性失败（弹窗验证不过 / rowBuyPoint 为 null / confirmPt 为 null）才封顶，点击没生效（弹窗没出现、按钮仍可购买）永不封顶。

## 7｜BUG修复_点错行活锁（玩家实机报告）
根因：行容差取图标框高×2（≈300px）**大于行距 260px（屏高 20%）**，相邻行互相看见对方的文本/图标。
要点：①`rowPitch` 由购买文本 y 间距**最小值**推导（过滤 <imgH×8%，`coerceIn(imgH×0.10, imgH×0.28)`），`rowTol=rowPitch×0.45` 恒 < 行距/2（取最小值而非中位数：漏读一行时中位数会翻倍）。②`rowBuyPoint` 改"全局最近+容差检查"，够不着不点；`rowConfirmed` 只用 `c.tol`（旧版 `max(b.h×2, c.tol)` 同样越界）。③点错行立即止损：弹窗商品名 `dialogItemKind` 明确≠目标 → `handledAdd` 放弃并报 `err_row_wrong_target`；读不到/一致才走重试预算（否则价格抖动会被误判成点错行，退化成更糟的漏买）。
影响：只改"候选→点击点"映射，YOLO/OCR 零影响（逐张候选与修复前逐字一致）。验证：回归台✓ 召回 100%(14/14)、误检 3、均值 684→632ms。
遗留：夹取下限是屏高 10%（1272 屏→127px），行距小于此仍有跨行风险（E7 固定≈20% 不触发）。

## 8｜UI_DEDUP_20260919 · 主题去重
根因：两主题各约 1000 行，逻辑与共用弹窗重复；BA 曾漏 `riskAccepted` 闸门 → 点开始无反应。
要点：①先搭"看不见图"的对拍链：`uiautomator dump` 元素树（主门禁）+ `screencap` numpy 逐像素（副门禁），并先测同版本连拍噪声底线。②新增 `ui/UiCommon.kt`：`stageResOf`、`rememberImagePicker`（选图+持久化权限+失败只记日志，原 4 份）、`RiskConfirmDialog`。③布局层不动。
影响：渲染等价。验证：元素树 8 屏/221 元素**零差异**、像素 6 屏 0.000%；`steam_about`/`ba_run` 4.29%/0.10% 与同版本噪声 4.29%/4.42% 一致=动画噪声；仅覆盖 4 页×2 主题。

## 重复内容清单（可合并）
1. **主题去重结论**被 round2/3/4/5 与 UI_DEDUP 复述 5 次：≥8 行连续相同块仅 12 块/145 行（最大 25 行在 Row 内）、"需重构两套布局树+真机逐屏比对"、重复行 420→300。
2. **FSM 单测难点**（round2/3 重复）：纯 JVM 里 android.* 全是默认值；出路是 Robolectric 或把 Bitmap 抽象成 Frame。
3. **验证模板**：每轮"BUILD SUCCESSFUL + N 单测全绿 + lint 门禁 + apksigner 同证书 + adb install -r"。
4. **双管线一致性**：ACTION→COMMIT（§1）→ 抽 BudgetGate（§4）→ 两管线各配 FSM 测试（§6），同一主题的三次递进，可并成一条原则。
5. 版本口径：§1/§2"仍属 V1 不升版本号"是 §3"15→16 每轮递增"的前态；`riskAccepted` 闸门（§1 BA 静默失效 / §8 共用弹窗）同源；无障碍更新后失效（§1 需重绑 / §3 坑）重复。

## 删除风险评估（删文件只留上面摘要会丢的具体细节）
- **§1**：`guardedTap=false` 的记账例外；`decodeFileSampled/decodeStreamSampled` 的 `inJustDecodeBounds`+2 的幂 `inSampleSize`；`didScroll` 缓冲 2×8.7MB；`handledRows.clear()` 移进 bot 线程（join 旧线程后）；`Humanizer` 4 处 sleep→`sleepQuietly`；崩溃日志路径 `files/e7sa_crash.log` 上限 1MB；`det_target_size` 默认 320；backup 排除 `external` 整域及 25MB 配额理由；`ClickGateTest` 四种拒点（停止/NaN/负坐标/越界）与 `PurchaseGuardTest` 语义（价格出现两次拒绝、7 位数不被 6 位前缀匹配）。
- **§2**：`takeFailure()` 的 4 处调用点；日志游标 lastLoggedStage/Error/Bm/Medal/Refresh 共用一把锁；`onUnbind` 清单例；`RailFloatySkin` 拉丁取首词、CJK 取 2 字；WebDAV 2MB 的 Content-Length + chunked 限长读；`get_rotate_crop_image` 的 `rw<=0`→`rh*48/0=inf`→warpAffine 异常穿透 JNI=std::terminate；删 OpenMP 区内的 `cv::setNumThreads(1)`；`kDetDiag` 开关（640×640≈41 万次 `pred.at<uchar>`）；lint 基线 112 条；死字段 `RunLog.enabled`。
- **§3/§4**：密钥细节（RSA4096/10000 天、别名 e7sa、SHA-256 4D:7A:DA:D4…、口令位置 `KEYSTORE-README.txt`、`keystore.properties` 入 .gitignore）；25 个常量的确切数值（只有 TuningTimingTest 钉住）；`goldSpent=184000`/`bookmarksGot=5` 金值；Robolectric 镜像配置与 `@Config(sdk=[34])`、"卡十几分钟"的现象。
- **§7**：全部阈值与函数名（`rowPitch`/`rowTol`/`rowBuyPoint`/`rowConfirmed`/`dialogItemKind`、0.45、8%/10%/28%、260px、300px、127px）；两条日志（`E7SA.Row WRONG TARGET` vs `dialog verify FAIL`）；相邻路径复核（`slot6Check/revealSlot6` 有上限+fail-closed、`handledContains` 7%≈89px）；回归台三指标与"逐张候选逐字一致"。
- **§8**：对拍三条命令与噪声底线（6/8 屏 0.000%、4.3%）、221 元素/8 屏、抽取的三个函数名。
- **跨文件**：各轮"未做项+理由链"（release 密钥属用户决策、`MIN_ROW_TOL=40f` 评估后不动、主题布局去重搁置）——摘要只剩结论，判断依据会丢。