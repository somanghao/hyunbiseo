plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 현비서 폰 앱 — 차 현비서와 같은 서버를 쓰는 별도 앱(차 앱 폰 모드와 별개). 차에서 물어본 것들이 피드로 자동 동기화되고,
// 장소 카드는 가게 상세([전화] [지도])로 · "폰으로 보내 줘"는 알림까지. 서버 짝 = server/hyun/features/phonelink.py
// 명세 = makecar-hy dashboard/hyun/specs-draft/phone-app.md
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
}
