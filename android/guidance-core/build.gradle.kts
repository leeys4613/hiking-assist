plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 실코스 패키지를 테스트 리소스처럼 읽는다 (testdata/ 는 저장소 루트)
    systemProperty("testdata.dir", rootProject.projectDir.parentFile.resolve("testdata").absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}
