plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 현비서 — 조수석에서 목적지까지 함께 가는 대화형 비서. 두뇌는 PC의 Claude Code 세션(server/hyun/)이고,
// 앱은 듣기(STT)·말하기(TTS)·주행 맥락(속도·기어·목적지·배터리·날씨)·안전 규칙을 맡는다.
android {
    namespace = "io.github.somanghao.makecar.hyun"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.github.somanghao.makecar.hyun"
        minSdk = 28
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

dependencies {
    // Pleos Connect SDK — 버전 정본: .claude/docs/wiki/pleos-sdk-overview.md
    implementation("ai.pleos.playground:Vehicle:2.3.2")        // 배터리
    implementation("ai.pleos.playground:NaviHelper:3.0.5")     // 목적지·남은 거리/시간·현재 위치(주소)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")   // 말하기
    implementation("ai.pleos.playground:SpeechToText:3.3.1")   // 듣기
}
