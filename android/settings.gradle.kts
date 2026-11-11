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
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }   // Pleos SDK
    }
}

rootProject.name = "hyunbiseo"
include(":hyun")
include(":hyunphone")   // 현비서 폰 앱(차 현비서와 동기화) — 차 앱 폰 모드와 별개
