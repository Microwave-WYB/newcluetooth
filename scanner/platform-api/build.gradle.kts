// :platform-api (lane B, docs/v2-api.md §4 and §5.2): the platform's types and interfaces. An Android library only
// because `Permissions` names `ActivityResultContract` and `ComponentActivity`; everything else is plain Kotlin.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.api.platform"
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
    api(project(":core-api"))
    api(libs.androidx.activity)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
