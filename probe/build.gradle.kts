plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jamkkanman.probe"
    compileSdk = 35

    // Android Automotive 표준 차량 API(CarPropertyManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "dev.jamkkanman.probe"
        minSdk = 28
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Pleos SDK는 이 테스트 앱에서 리플렉션으로만 호출해, API 이름이 달라도 빌드는 깨지지 않게 함
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
