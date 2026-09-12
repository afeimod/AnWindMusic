plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============================================================
// AnWindMusic —— AnWind 云音乐独立版
// 从 AnWind（https://github.com/afeimod/AnWind）云音乐应用
// 整体移植而成的独立安卓音乐播放器，包名 com.anwindmusic。
// ============================================================

android {
    namespace = "com.anwindmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anwindmusic"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ============================================================
    // 签名配置
    // ============================================================
    // 优先使用环境变量指定的 release keystore（CI 环境会自动生成）；
    // 环境变量不存在（本地开发）时回退 debug 签名。
    //
    // ⚠️ 重要：debug 签名的 release APK 会被系统标记为 testOnly=true，
    //    Android 14 系统安装器会拒绝直接安装（需 adb install -t），
    //    所以 CI 构建必须用 release keystore 签名。
    // ============================================================
    val keystorePath = System.getenv("KEYSTORE_PATH")
    val keystorePass = System.getenv("KEYSTORE_PASS")
    val keyAlias = System.getenv("KEY_ALIAS") ?: "anwindmusic"
    val keyPass = System.getenv("KEY_PASS")

    signingConfigs {
        create("release") {
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass ?: keystorePass
                println("✅ Using release keystore from: $keystorePath")
            } else {
                println("⚠️  No release keystore found, release APK will use debug signing (testOnly)")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI 存在 release keystore 时用其签名；否则回退 debug 签名
            signingConfig = if (keystorePath != null && file(keystorePath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // 1.5.15 与 Kotlin 1.9.25 官方配对
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // 保险丝：lint 错误不中断 release 构建（报告仍生成在 build/reports/）
        abortOnError = false
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // androidx.media：MediaSessionCompat + MediaStyle 通知（锁屏/耳机线控）
    implementation("androidx.media:media:1.7.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
