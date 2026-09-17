# E7SA 代码质量重构记录（2026-09-17）

## 一、这次做了什么

目标：把代码质量从「能用」提到「可长期维护」，且**不改变任何行为**。

验收标准（全部达成）：

| 项目 | 重构前 | 重构后 |
|---|---|---|
| 最大文件 | `ShopAccessibilityService.kt` 80 KB / 1690 行 | `ThemeSteam.kt` 55 KB（纯 UI） |
| 服务类 | 1690 行，46 个函数，上帝类 | 601 行，只负责生命周期与会话编排 |
| 单元测试 | 0 | 55 个（全绿） |
| 静默 catch | 18 处 | 0 处 |
| 魔数 | 散落 3 个文件 | 集中在 `bot/Tuning.kt`（28 个具名常量） |
| 回归台 | 召回 100% / 误检 3 | 召回 100% / 误检 3（**未退化**） |

## 二、新目录结构

```
com/e7/shop/
├── ShopAccessibilityService.kt   601 行  生命周期 · 会话编排 · BotState 发布
├── MainActivity.kt                       Compose 宿主与导航
├── bot/
│   ├── BotEngine.kt                      传统点击状态机（FSM）
│   ├── AiBotEngine.kt                    AI 点击状态机（独立闭环）
│   ├── Recognition.kt                    双引擎识别接口 + 共享语义层
│   ├── ClickPlanner.kt                   AI 点击的视觉工具箱
│   ├── ClickGate.kt                      最终点击闸门（两管线共用）
│   ├── Humanizer.kt                      拟人化
│   ├── ShopStateTracker.kt               商店状态差分（观测层）
│   └── Tuning.kt                    ★   全部调参常量集中地
├── device/
│   └── DeviceIo.kt                  ★   截图 / 点击 / 滑动 / 等待
├── diag/
│   └── DiagnosticsRunner.kt         ★   回归基准 / 原始截图采集
├── ui/
│   ├── ThemeCore.kt                 ★   E7Theme 接口 + ThemeRegistry
│   ├── ThemeSteam.kt                ★   Steam 深色 + OLED 两套主题
│   ├── ThemeBlueArchive.kt          ★   Blue Archive 主题
│   ├── ThemeShared.kt               ★   跨主题共享小工具
│   ├── FloatyController.kt          ★   悬浮窗任务面板
│   ├── SettingsSchema.kt                 设置项唯一权威定义
│   └── DfibEasing.kt                     缓动族
├── data/                                 配置 / 记录 / 日志
├── net/                                  WebDAV 同步
└── score/                                装备评分
```

★ = 本次新增文件

## 三、为什么这样拆

### 1. 服务类只留「机器人怎么跑」

原先 `ShopAccessibilityService` 同时承担：无障碍生命周期、前台服务保活、截图、手势、
悬浮窗（三套主题各一套 View 构建）、状态发布、会话管理、帧耗时测量、WakeLock、诊断基准。

拆分依据是**关注点**而不是行数：
- 悬浮窗 → `ui/FloatyController`：纯 UI，只读 BotState、只画 View、只回调控制指令
- 截图与手势 → `device/DeviceIo`：设备外设驱动，不认识商店也不做决策
- 诊断基准 → `diag/DiagnosticsRunner`：只在长按版本号时触发，与主循环无关

### 2. 主题按「设计语言」拆文件

`Themes.kt` 原先 2286 行装了三个主题。现在按设计语言分文件 ——
新增第四个主题时只需新建一个文件并实现 `E7Theme` 接口，不用碰已有主题。

### 3. 常量集中到 `Tuning`

原先阈值以裸字面量散落各处（`0.10f`、`0.45f`、`0.035f`……）。后果：
想统一调「按钮判定灵敏度」要翻三个文件改十几处；同名值无法判断是巧合还是必须一致。

现在每个常量都有名字和出处说明。**改这里等于改行为**，改完必须跑回归台。

已验证：28 个常量的数值与重构前的字面量**逐一比对一致**。

### 4. 依赖注入而非继承

`DiagnosticsRunner` 与 `DeviceIo` 都通过构造参数接收能力（assets / 截图 / 运行态），
不持有 Service 引用。好处是纯逻辑可以在 JVM 单测里直接跑 ——
`parseGtText` 因此从「无法测试的私有方法」变成「可测的伴生函数」。

## 四、单元测试（55 个）

```
app/src/test/java/com/e7/shop/
├── bot/RecognitionLogicTest.kt     27 个
├── score/ScoreEngineTest.kt        19 个
└── diag/DiagnosticsRunnerTest.kt    9 个
```

覆盖的是**踩过坑的地方**，不是凑覆盖率：

- **行容差必须由行距推导** —— 跨行点错的活锁根因。测试锁死「容差 < 行距一半」
  和「行距取最小值而非中位数」（OCR 漏读一行时中位数会翻倍）。
- **刷新弹窗不能误判成购买弹窗** —— 顺序错了会误买。
- **购买键定位必须 fail-closed** —— 够不着就返回 null，绝不点相邻行。
- **评分权重** —— 速度 ×2.0 / 暴击率 ×1.5 / 暴击伤害 ×1.1 / 固定攻击 ×0.3。
  权重错了玩家会按错误分数卖装备，不可逆。

运行：

```
gradle testReleaseUnitTest
```

注意：`org.json` 在单元测试里必须用真实依赖（`testImplementation("org.json:json:...")`），
因为 `android.jar` 是**桩**，所有方法返回默认值 —— 否则标注解析类测试会全部假失败。

## 五、行为不变性的验证

1. **常量逐一比对**：28 个 `Tuning` 常量 vs 重构前字面量，全部一致。
2. **安全谓词完整性**：`rowBuyPoint` / `rowConfirmed` / `rowSoldOut` / `dialogConfirmed` /
   `priceMatches` / `sceneOf` / `frameFingerprint` 全部保留。
3. **公开 API 未变**：`startBot` / `pauseBot` / `resumeBot` / `stopBot` / `addStateListener` /
   `BotState` / `Stage` 等 15 个对外符号签名不变，MainActivity 与主题无需改动。
4. **真机回归台**：召回 100%、误检 3、P95 690ms —— 与基线一致（历史 30 次记录中
   召回始终 100%、误检始终 3）。
5. **真机冒烟**：安装后启动无崩溃，无障碍服务绑定成功，
   `PP-OCRv5 load=true` / `YOLOv8 load=true`，前台服务建立成功。

## 六、后续可做（本次未做）

- `ThemeSteam.kt` 仍有 1236 行（含两个主题的完整四页布局）。可按页面继续拆
  （Home / Records / Profile / Settings 各一文件），但收益递减。
- `BotEngine` 与 `AiBotEngine` 有部分重复的会话管理骨架（fatigue 漂移、capsReached、
  session 收尾）。可抽 `SessionRunner` 基类，但两条管线刻意保持独立，
  抽取需谨慎不要引入耦合。
- 截图仍是无障碍 API（约 400ms/帧）。若未来上 Root，`DeviceIo` 是唯一需要改的地方 ——
  这正是本次拆分的价值。
