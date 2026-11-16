// 專案總設定：告訴 Gradle（組裝工具）去哪裡下載零件，以及這個專案包含哪些模組。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PlaylistMirror"
include(":app")
