# E7SA 第二轮优化（2026-09-19 晚）

> 触发：用户确认「数据已上传到坚果云」+「允许卸载重装」→ 解锁了正式签名密钥这一项
> 产物：`E7SA-V1.0-vc16.apk`（49,849,170 字节，**正式密钥签名** CN=E7SA，versionCode **16**）

## 一、正式签名（原"未做项 ④"，现已完成）

- 新建 `keystore/e7sa-release.jks`（RSA 4096 / 10000 天），别名 `e7sa`，
  证书 SHA-256 `4D:7A:DA:D4:…:A0:33`；口令与说明见 `keystore/KEYSTORE-README.txt`。
- `app/build.gradle.kts` 从 `keystore.properties` 读取签名配置（该文件已加入 .gitignore），
  **gradle 直接产出已签名 APK**，不再需要手工 zipalign + apksigner。
- 另一份备份存放在**项目目录之外的本地位置**（不随仓库分发；请作者自行保管）。
- **⚠️ 密钥与口令丢失 = 再也无法给已安装用户发更新**（Android 只接受同签名覆盖安装）。

## 二、versionCode 15 → 16

旧 debug 系与新正式签名系是两条线（无法互相覆盖升级）；且此前多轮构建共用 15，
群友反馈问题时无法从版本号分辨装的是哪一版。本次起每轮递增。

## 三、时序魔数收敛到 Tuning（原"未做项 ③"，现已完成）

- 两个引擎里 **25 处**裸 sleep/randInt 字面量 → 命名常量（`POLL_TICK_MS`、
  `DIALOG_POLL_MIN/MAX_MS`、`RESHOT_RETRY_MS`、`SETTLE_AFTER_RECYCLE_MS` …）。
- **数值一个都没改**（纯改名），行为等价；同一语义的值（如 350~600 弹窗轮询）
  现在两条管线共用同一个常量，杜绝"改一处漏一处"。

## 四、验证

| 项 | 结果 |
|---|---|
| `assembleRelease` + `lintRelease` + `testReleaseUnitTest` | **BUILD SUCCESSFUL**（89 单测全绿，lint 门禁通过） |
| 签名 | apksigner 验证：CN=E7SA / 4d7adad4…（正式密钥） |
| 卸载重装 | `adb uninstall` → `adb install` **Success**（换签名后必须卸载，数据从云端恢复） |
| 权限重建 | WRITE_SECURE_SETTINGS granted=true、POST_NOTIFICATIONS granted=true、SYSTEM_ALERT_WINDOW=allow、无障碍服务已回到 Enabled 列表 |
| 冒烟 | MainActivity 正常聚焦、33 条界面文本、无崩溃 |

## 五、仍未做（本轮评估后**主动不做**，附理由）

1. **主题文件去重**：实测两文件"≥8 行的连续相同块"只有 **12 块 / 145 行**，
   且都是**夹在各自布局里的片段**（最大 25 行，在 Row 内部）——
   抽取等于重构两套布局树，而 UI 布局改动**无法在本机做视觉验证**
   （截图我看不了），回归风险 > 收益。要做的话建议单独立项 + 真机逐屏比对。
2. **决策状态机单测**：BotEngine/AiBotEngine 的 Host 依赖 `AppConfig`（需要 Context），
   且引擎要真实 `Bitmap` 才能推进 —— 纯 JVM 单测里 android.* 全是默认值。
   可行路径有两条：①引入 Robolectric（本机 gradle 缓存里没有，要下载较重的依赖）
   ②把"帧"抽象出来（Bitmap → 接口）。两条都是独立一轮的工作量，未在本轮动。
