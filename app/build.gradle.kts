// App 本身的組裝設定：名稱、支援的 Android 版本、用到的函式庫。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.playlistmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.playlistmirror"
        // Quest 2 是 Android 10（API 29），所以最低支援到 29
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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
        // 讓程式可以讀到版本號（設定頁會顯示）
        buildConfig = true
    }
}

dependencies {
    // Jetpack Compose：用程式碼畫畫面的工具組，版本由 BOM 統一管理
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // 讀寫 JSON（歌曲資料檔與歌單檔都是 JSON 格式）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 在背景做慢的事情（讀檔、算指紋），不讓畫面卡住
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 單元測試工具
    testImplementation("junit:junit:4.13.2")
}
