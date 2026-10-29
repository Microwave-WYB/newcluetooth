import java.time.Duration

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.sheets"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    lint {
        abortOnError = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Roborazzi: -Pcluetooth.record=true rewrites the references under src/test/screenshots; the
                // default compares against them.
                val record = providers.gradleProperty("cluetooth.record").orNull == "true"
                test.systemProperty("roborazzi.test.record", record.toString())
                test.systemProperty("roborazzi.test.verify", (!record).toString())
                test.inputs.dir(layout.projectDirectory.dir("src/test/screenshots")).optional()
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":ui-contract"))
    implementation(project(":platform-api"))
    implementation(project(":designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Screenshots of the sheet screens on the fakes (Robolectric + Roborazzi, as :designsystem does).
    testImplementation(project(":core-fake"))
    testImplementation(project(":platform-fake"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

// A hung UI test fails the task instead of holding the build.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(8))
}
