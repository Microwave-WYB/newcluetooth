// :platform-fake (lane B, docs/v2-api.md §6.3): deterministic fakes of :platform-api for the gallery and the UI
// lanes' tests. It never depends on :core-fake; FakeRecorder and FakeGattRunner take callbacks instead.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.platform.fake"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":platform-api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
