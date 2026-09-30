// Pure Kotlin/JVM: no Android imports can compile here. That keeps the recorder
// testable with mocks, like the iOS Core/ folder.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
