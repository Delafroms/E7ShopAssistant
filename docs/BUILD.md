# 构建说明（E7SA）

本文件说明如何从源码构建 E7SA。**CI 只跑单元测试与 Lint**，不构建 APK —— 原因见文末。

## 1. 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 构建脚本用 Kotlin DSL；JDK 17 亦可，实测用 Zulu 21 |
| Gradle | **8.11.1** | 仓库**暂无 Wrapper**，请自行安装或使用同版本 |
| Android SDK | **platform 36 + build-tools 36.0.0** | `compileSdk = 36` |
| Android NDK | **29.0.13113456** | 仅构建原生层时需要 |
| CMake | 3.22.1 | 由 AGP 调用 |

## 2. 原生层依赖（必须自备）

以下两个目录**不在仓库中**（体积合计约 146MB，见 `.gitignore`），构建原生层前必须自行准备：

```
app/src/main/jni/ncnn-arm64-v8a/           # ncnn（含 include/ 与 lib/）
app/src/main/jni/ncnn-armeabi-v7a/
app/src/main/jni/opencv-mobile-4.13.0-android/
```

- **ncnn**：从 [Tencent/ncnn releases](https://github.com/Tencent/ncnn/releases) 取 Android 预编译包（含头文件与 `libncnn.a`），按上面的目录名放置。
- **opencv-mobile**：从 [nihui/opencv-mobile releases](https://github.com/nihui/opencv-mobile/releases) 取 **4.13.0** 的 Android 包。

## 3. 构建命令

```bash
# 完整 Release 构建（需要上面的原生依赖 + 签名密钥）
gradle :app:assembleRelease

# 只跑单元测试与 Lint（跳过原生层，CI 用的就是这条）
gradle :app:testReleaseUnitTest -PskipNative=true
gradle :app:lintRelease -PskipNative=true
```

**`-PskipNative=true` 的作用**：跳过 `ndkVersion` 与 `externalNativeBuild` 配置，
从而不要求安装 NDK、也不要求原生依赖存在。它产出的产物**不含原生库**，仅用于跑测试与静态检查。

## 4. 签名（可选）

`app/build.gradle.kts` 会在项目根存在 `keystore.properties` 时自动签名：

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

该文件与密钥库**均不入库**。没有它时，`assembleRelease` 产出的是未签名包。

## 5. 为什么 CI 不构建 APK

1. 原生依赖（约 146MB）未入库；
2. 只要声明 `ndkVersion`/`externalNativeBuild`，AGP 在**配置阶段**就会要求安装对应 NDK，
   而 GitHub 托管运行器未接受该 SDK 许可证 → 失败：
   `LicenceNotAcceptedException: Failed to install ... ndk;29.0.13113456`；
3. 因此 CI 用 `-PskipNative=true` 只跑测试与 Lint。

若将来需要 CI 构建 APK，可选方案：把原生依赖做成构建步骤从上游 releases 下载解压，
或使用自托管 runner（已装好 NDK 与许可证）。
