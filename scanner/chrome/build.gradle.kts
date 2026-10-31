// :chrome (lane C2, docs/v2-api.md §6.4): the recorder, sidebar, search page and bar, top row, Settings and onboarding.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.chrome"
    compileSdk = 35

    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                val record = providers.gradleProperty("cluetooth.record").orNull == "true"
                test.systemProperty("roborazzi.test.record", record.toString())
                test.systemProperty("roborazzi.test.verify", (!record).toString())
                test.inputs.dir(layout.projectDirectory.dir("src/test/screenshots"))
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    api(project(":designsystem"))
    api(project(":ui-contract"))
    api(project(":core-api"))
    api(project(":platform-api"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.test.manifest)
}
