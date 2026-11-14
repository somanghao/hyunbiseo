import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 현비서 폰 앱 — 차 현비서와 같은 서버를 쓰는 별도 앱(차 앱 폰 모드와 별개). 차에서 물어본 것들이 피드로 자동 동기화되고,
// 장소 카드는 가게 상세([전화] [지도])로 · "폰으로 보내 줘"는 알림까지. 서버 짝 = server/hyun/features/phonelink.py
// 명세 = makecar-hy dashboard/hyun/specs-draft/phone-app.md
// 제출용 release 서명 — 키는 저장소 밖 ~/.android/hyunbiseo-release.properties(storeFile·storePassword·keyAlias·keyPassword).
// 그 파일이 없는 PC에선 서명 없이 빌드된다(디버그 빌드는 상관없음).
val releaseKey = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android/hyunbiseo-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.somanghao.makecar.hyunphone"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.somanghao.makecar.hyunphone"
        minSdk = 28          // 차 앱과 같게
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (releaseKey.isNotEmpty()) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (releaseKey.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
}
