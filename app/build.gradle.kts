plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nlmthai.telerec"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nlmthai.telerec"
        // Android 11: CONTROL_ZOOM_RATIO, and MediaStore saving without a storage permission.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // -PciqTethered=true talks to the Connect IQ simulator over adb instead of a real watch.
        buildConfigField("boolean", "CIQ_TETHERED", (project.findProperty("ciqTethered") ?: "false").toString())
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.connectiq) { artifact { type = "aar" } }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
