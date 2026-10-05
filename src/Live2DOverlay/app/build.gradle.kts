import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.live2d.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.live2d.overlay"
        minSdk = 24
        targetSdk = 34
        versionCode = 34
        versionName = "1.7.27"
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        // v1.7.1（迭代清单 P0-4）：ACTION_DEBUG_JS 需要 BuildConfig.DEBUG 做守卫。
        // AGP 8 起 BuildConfig 默认**不生成**，必须显式打开，否则报
        // "Unresolved reference 'BuildConfig'"。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    // v1.7.12：API Key 加密存储（用户选择「应用内输入 + EncryptedSharedPreferences」）
    // 底层用 Android Keystore 的 AES-256-GCM，密钥不出 TEE/StrongBox。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

// ---------------------------------------------------------------------------
// 离线运行库准备：优先使用仓库内已存在的文件；缺失时才尝试联网下载。
// 编译环境无外网时不会阻塞构建（任务内部吞掉异常，仅打印警告）。
// ---------------------------------------------------------------------------
val live2dRuntimeDir = layout.projectDirectory.dir("src/main/assets/live2d/runtime")

val runtimeLibs = mapOf(
    "pixi.min.js" to "https://cdn.jsdelivr.net/npm/pixi.js@6.5.10/dist/browser/pixi.min.js",
    "pixi-live2d-display.min.js" to "https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/cubism4.min.js",
    "live2dcubismcore.min.js" to "https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js",
    "live2d.min.js" to "https://cdn.jsdelivr.net/gh/dylanNew/live2d/webgl/Live2D/lib/live2d.min.js"
)

tasks.register("prepareOfflineLive2DRuntime") {
    description = "确保 Live2D 离线运行库存在于 assets（缺失时尝试下载）"
    group = "build"

    doLast {
        val dir = live2dRuntimeDir.asFile
        if (!dir.exists()) dir.mkdirs()

        runtimeLibs.forEach { (name, url) ->
            val target = File(dir, name)
            if (target.exists() && target.length() > 1024) {
                logger.lifecycle("[live2d] 已存在，跳过：$name (${target.length()} bytes)")
                return@forEach
            }
            try {
                logger.lifecycle("[live2d] 下载 $name ...")
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                logger.lifecycle("[live2d] 完成 $name (${target.length()} bytes)")
            } catch (t: Throwable) {
                logger.warn("[live2d] 下载失败 $name → ${t.message}；" +
                        "请手动放置该文件到 src/main/assets/live2d/runtime/")
            }
        }
    }
}

tasks.matching { it.name.startsWith("preBuild") || it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }
    .configureEach { dependsOn("prepareOfflineLive2DRuntime") }

