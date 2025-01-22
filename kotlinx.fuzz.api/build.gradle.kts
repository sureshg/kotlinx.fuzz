plugins {
    id("kotlinx.fuzz.src-module")
}

dependencies {
    implementation(libs.rgxgen)
    implementation(libs.junit.platform.engine)
    implementation(kotlin("reflect"))

    testRuntimeOnly(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
