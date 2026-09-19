plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.e7.shop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.e7.shop"
        minSdk = 30
        targetSdk = 36
        versionCode = 15
        versionName = "1.0"

        ndk {
            // real phones only: keep the universal APK lean (no emulator ABIs)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        ndkVersion = "29.0.13113456"   // r29: clang 20 libomp has __kmpc_dispatch_deinit

        // PP-OCRv5 (ncnn) native OCR engine - replaces slow ML Kit
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++11")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
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
    // 真实的 org.json 实现：Android 的 android.jar 在单元测试里是**桩**，
    // 所有方法返回默认值（JSONObject 会返回 null / 空），
    // 于是标注解析这类逻辑在 JVM 上永远失败。加上这个依赖才能真正测到逻辑。
    testImplementation("org.json:json:20240303")
}
