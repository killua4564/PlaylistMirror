// 最外層的組裝設定：只宣告要用哪些外掛與版本，實際設定在 app/build.gradle.kts。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
