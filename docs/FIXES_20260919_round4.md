# E7SA 第四轮（2026-09-19 深夜）：Robolectric 引入 + 决策状态机整机测试

> 触发：用户批准引入 Robolectric（原话「Robolectric吗，也就是要下AVD对吧，我许了，这本来是跨分辨率用到的。继续优化吧」）
> 澄清：**Robolectric 不需要 AVD / 模拟器 / 真机** —— 它在电脑 JVM 上跑"影子 Android"，
> 下载的是 android-all 框架 jar（约 100MB），换来真 Bitmap + 真 Context。
> 产物：测试代码（app/src/test）+ 构建配置；**生产代码本轮零改动**（因此 APK 行为与 vc16b 一致）。

## 一、为什么值得做

烧钱的那个 P0（记账被验证门控）里，**每一个函数单独看都是对的** —— 错的是**顺序**：
钱花了却没记账。纯函数单测永远抓不到这类问题，只有把状态机推着走一遍、
在"点下确认"之后立刻检查账本，才能发现。这就是 FSM 整机测试的价值。

## 二、新增测试（全部通过）

| 文件 | 内容 |
|---|---|
| `RobolectricProbeTest` | 探针：真 Bitmap（可读写像素）+ 真 AppConfig（落 SharedPreferences） |
| `BotEngineFsmTest` | ① **钱路径**：驱动引擎走完 SCAN→SHOP_SCAN→BUYING→CONFIRM→VERIFY，真的买到 5 个书签 → 断言 `goldSpent=184000`、`bookmarksGot=5`、行被标记已处理、**金币闸门在下一轮触发并标记正常完成** ② **漏买回归**：点击后弹窗不出现且该行仍可购买 → 断言**不得**标记该行已处理 ③ **fail-closed**：零感知输入下**一次都不点**、不滑动 |

测试总数 **102 → 107**，全绿。

## 三、过程中暴露的三条真实规则（现在被测试钉住了）

1. **`dialogBuy` 要求先看到「取消」按钮**，且购买键必须在它右边 —— 防误点设计。
   假弹窗少了取消键，确认点击会被正确拦下（第一版测试就是这么失败的）。
2. **`dialogConfirmed` 是三重验证**：商品名 + **图标旁证**（YOLO 框或颜色签名）+ 价格唯一匹配
   （恰好一条 6 位数字等于期望价格）。假弹窗缺图标框时验证正确地拒绝。
3. **点击没生效时，引擎会按设计反复重试同一行**（"不放弃可买的行"），
   而不是标记已处理 —— 这正是漏买修复的语义。

## 四、构建环境（重要）

- Robolectric 的 android-all 是**测试运行时**才下载的，读的是测试 JVM 的系统属性，
  **不继承 Gradle 的仓库/代理配置** → 国内直连 Maven Central 会卡住（实测卡十几分钟）。
  已在 `app/build.gradle.kts` 指定阿里云公共镜像（`robolectric.dependency.repo.url`）。
- 测试用 `@Config(sdk = [34])`，避免依赖 SDK 36 的 android-all。

## 五、仍未做

- **AI 管线（AiBotEngine）的 FSM 测试**：它内部硬编码 `YoloEngine()`，且 `run()` 先检查
  `YoloDet.loaded`（`private set`，测试无法置位）。要覆盖它需要两处小改动
  （构造函数注入视觉引擎 + `internal set`），本轮未动，留给下一轮。
- **主题「布局」去重**：同前（需真机逐屏视觉比对）。
