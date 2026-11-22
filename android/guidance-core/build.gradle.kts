plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 실코스 패키지를 테스트 리소스처럼 읽는다 (testdata/ 는 저장소 루트)
    systemProperty("testdata.dir", rootProject.projectDir.parentFile.resolve("testdata").absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

// GPX 재생 시뮬레이터 CLI:  ./gradlew :guidance-core:replay --args="testdata/suraksan_package.json testdata/scenarios/real_departure.gpx"
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "GPX 를 guidance-core 에 재생해 안내 이벤트를 출력한다"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.hiking.guidance.Replay")
    workingDir = rootProject.projectDir.parentFile
}
