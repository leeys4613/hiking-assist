plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.hiking"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hiking"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Phase 1: 코스 패키지는 testdata/ 의 하드코딩 JSON 을 그대로 assets 으로 쓴다 (Phase 2 에서 서버 다운로드로 교체)
    sourceSets["main"].assets.srcDir(rootProject.projectDir.parentFile.resolve("testdata"))
}

dependencies {
    implementation(project(":guidance-core"))
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime:2.9.1")
}
