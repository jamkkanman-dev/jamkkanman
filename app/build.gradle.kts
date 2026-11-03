import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Pleos 앱 등록 정보는 local.properties에만 둔다 (깃허브에 올라가지 않음)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.jamkkanman.app"
    compileSdk = 35

    // 기어 레버 위치(GEAR_SELECTION)는 Android Automotive 표준 차량 API로 읽는다
    useLibrary("android.car")

    defaultConfig {
        applicationId = "dev.jamkkanman.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localProps.getProperty("pleos.clientId", "")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localProps.getProperty("pleos.clientSecret", "")}\"")
    }

    buildFeatures {
        buildConfig = true
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 차량 신호: 기어(P), 방향지시등(비상등), 문 열림
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 내비 연동: 현재 위치, 목적지, 주의 지점, 경유지 추가
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // 음성 안내: 에뮬레이터·차량의 Pleos 음성 엔진 (안드로이드 기본 음성 엔진이 없는 환경 대응)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}
