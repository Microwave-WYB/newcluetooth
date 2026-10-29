// :gallery (docs/v2-api.md §6.0): a debug-only app that shows every lane's components on fakes. Lane B owns the
// shell and the registry; each lane owns its own `lanes/<Lane>Gallery.kt` and adds its module dependencies here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.gallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.ucsd.sysnet.cluetoothscanner.gallery"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-gallery"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

// Debug only: there is no release gallery.
androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":platform-api"))
    implementation(project(":platform-fake"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
