import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名（2026-09-19）：从 keystore.properties 读取（该文件不入版本库）。
// 找不到时退回"不签名"，保证别人 clone 下来仍能构建（只是产物未签名）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/**
 * 是否跳过原生层（ncnn / OpenCV）构建，用 `-PskipNative=true` 开启。
 *
 * 为什么需要：原生层依赖两个**预编译目录**（`app/src/main/jni/ncnn-*` 与
 * `opencv-mobile-*`，合计约 146MB），它们没有入库（见 .gitignore），
 * 而且声明 `ndkVersion` 本身就会让 AGP 要求安装对应 NDK（CI 上会因未接受
 * SDK 许可证而失败：LicenceNotAcceptedException）。
 * 因此 CI 只跑「单元测试 + lint」这类不需要原生层的任务；完整 APK 构建需要
 * 先按 docs/BUILD.md 准备好原生依赖。
 */
val skipNative = (providers.gradleProperty("skipNative").orNull ?: "false").toBoolean()

android {
    namespace = "com.e7.shop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.e7.shop"
        minSdk = 30
        targetSdk = 36
        // 2026-09-19：15 → 16。原因：①换了正式签名密钥，这是新一系（旧 debug 系无法覆盖升级）；
        // ②此前多轮构建共用 15，出问题时无法从版本号分辨用户装的是哪一版（群友反馈的痛点）。
        versionCode = 16
        versionName = "1.0"

        ndk {
            // real phones only: keep the universal APK lean (no emulator ABIs)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        if (!skipNative) {
            ndkVersion = "29.0.13113456"   // r29: clang 20 libomp has __kmpc_dispatch_deinit
        }

        // PP-OCRv5 (ncnn) native OCR engine - replaces slow ML Kit
        if (!skipNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++11")
                }
            }
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            // R8 code & resource shrinking: the debug build was packing
            // classes.dex(7.8MB)+classes2.dex(4.3MB) unminified. Enabling
            // minify + shrinkResources removes dead code and unused resources
            // (~6MB saved) while keeping every feature - this is the safe
            // size reduction that keeps the APK above the 60MB floor.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // 新架构 GUI：Jetpack Compose (Material 3)。Kotlin 1.9.24 <-> Compose Compiler 1.5.14
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric（2026-09-19 引入）：在**电脑的 JVM** 上跑"影子 Android"，
        // 不需要 AVD / 模拟器 / 真机。它换来两件本机单测做不到的事：
        //  ① 能造真实 Bitmap（引擎的 FSM 才推得动 → 记账时序、点击后行为可测）
        //  ② 能拿到真实 Context（AppConfig/SharedPreferences 可实例化）
        // 注意：测试用 @Config(sdk=[34]) 固定版本，避免依赖 SDK 36 的 android-all。
        unitTests.isIncludeAndroidResources = true
        // Robolectric 的 android-all 是**测试运行时**才下载的（~100MB+），
        // 它读的是测试 JVM 的系统属性，而不是 Gradle 的仓库配置 —— 国内直连
        // Maven Central 会卡住（2026-09-19 实测：卡了十几分钟没动静）。
        // 这里显式指向阿里云公共镜像（它是 Maven Central 的完整镜像）。
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.id", "aliyun")
            it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
        }
    }

    lint {
        // 2026-09-19：建立 lint 基线，取代"`-x lint` 整体绕过"。
        // 基线冻结"当前已知问题"（含设计使然的 ProtectedPermissions），之后**新增**的问题才会报出来
        // —— 从"没人看的 200 条"变成"新问题即信号"。abortOnError=true 让它成为真门禁。
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- Compose Material 3 (新架构 GUI) ----
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // OkHttp (Jianguoyun WebDAV sync)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Shizuku（可选：无 Shizuku 时静默降级为系统设置引导）
    // 12.1.0：newProcess 为公开 API（13.x 已移除该公开接口），与新版 Manager 兼容
    implementation("dev.rikka.shizuku:api:12.1.0")
    implementation("dev.rikka.shizuku:provider:12.1.0")

    // OpenCV: NOT a Gradle dependency. The native layer (app/src/main/jni)
    // links the static opencv-mobile 4.13.0 libs via CMake, and no Kotlin code
    // touches org.opencv.* — the old org.opencv:opencv:4.10.0 artifact only
    // shipped a duplicate libopencv_java4.so per ABI.

    // ---- 单元测试 ----
    // 纯逻辑（关键词匹配 / 场景判定 / 行距与容差推导 / 标注解析 / 计分）不依赖
    // Android 框架，可以在 JVM 上直接跑。这些函数一旦出错就是"点错行/漏买"，
    // 用单测锁住比每次真机回归便宜得多。
    testImplementation("junit:junit:4.13.2")
    // FSM 驱动测试：真 Bitmap + 真 Context（见 testOptions 的说明）
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core:1.6.1")
    // 真实的 org.json 实现：Android 的 android.jar 在单元测试里是**桩**，
    // 所有方法返回默认值（JSONObject 会返回 null / 空），
    // 于是标注解析这类逻辑在 JVM 上永远失败。加上这个依赖才能真正测到逻辑。
    testImplementation("org.json:json:20240303")
}
