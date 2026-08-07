plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.gms.google-services")
}

val cluetoothCoreAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val v2UploaderEnabled = true
val mapsApiKey = providers.gradleProperty("CLUETOOTH_MAPS_API_KEY")
    .orElse(providers.environmentVariable("CLUETOOTH_MAPS_API_KEY"))
    .orNull.orEmpty()

val releaseSigningNames = mapOf(
    "storeFile" to "CLUETOOTH_RELEASE_STORE_FILE",
    "storePassword" to "CLUETOOTH_RELEASE_STORE_PASSWORD",
    "keyAlias" to "CLUETOOTH_RELEASE_KEY_ALIAS",
    "keyPassword" to "CLUETOOTH_RELEASE_KEY_PASSWORD",
)
val releaseSigningProperties = releaseSigningNames.mapValues { (_, name) ->
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
}
val hasAnyReleaseSigning = releaseSigningProperties.values.any { it != null }
val hasReleaseSigning = releaseSigningProperties.values.all { !it.isNullOrBlank() }
if (hasAnyReleaseSigning && !hasReleaseSigning) {
    val missingNames = releaseSigningProperties
        .filterValues { it.isNullOrBlank() }
        .keys
        .map { requireNotNull(releaseSigningNames[it]) }
        .sorted()
        .joinToString()
    throw GradleException(
        "Release signing is partially configured. Provide all four CLUETOOTH_RELEASE_* " +
            "values or none for an unsigned local release. Missing: $missingNames"
    )
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "edu.ucsd.sysnet.cluetoothscanner"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "0.0.5"
        buildConfigField("boolean", "V2_UPLOADER_ENABLED", v2UploaderEnabled.toString())
        buildConfigField("boolean", "MAPS_CONFIGURED", mapsApiKey.isNotBlank().toString())
        manifestPlaceholders["CLUETOOTH_MAPS_API_KEY"] = mapsApiKey

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*cluetoothCoreAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningProperties["storeFile"]))
                storePassword = releaseSigningProperties["storePassword"]
                keyAlias = releaseSigningProperties["keyAlias"]
                keyPassword = releaseSigningProperties["keyPassword"]
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/source/uniffi"))
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
        }
    }

    lint {
        abortOnError = false
    }
}

val cluetoothCoreDirectory = rootProject.layout.projectDirectory.dir("../cluetooth-core")
val cluetoothCoreInputs = fileTree(cluetoothCoreDirectory) {
    include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "uniffi.toml")
    include("src/**/*.rs")
}

val generateCluetoothCoreBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build the host Rust library and generate pinned UniFFI Kotlin bindings"
    workingDir(cluetoothCoreDirectory)
    commandLine(
        "bash",
        "scripts/generate-kotlin-bindings.sh",
        layout.buildDirectory.dir("generated/source/uniffi").get().asFile.absolutePath,
    )
    inputs.files(cluetoothCoreInputs)
    inputs.file(cluetoothCoreDirectory.file("scripts/generate-kotlin-bindings.sh"))
    outputs.dir(layout.buildDirectory.dir("generated/source/uniffi"))
}

val buildCluetoothCoreAndroid by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build and package the four pinned Rust Android ABIs at API 24"
    workingDir(cluetoothCoreDirectory)
    commandLine(
        "bash",
        "scripts/build-android.sh",
        layout.buildDirectory.dir("generated/jniLibs").get().asFile.absolutePath,
    )
    inputs.files(cluetoothCoreInputs)
    inputs.file(cluetoothCoreDirectory.file("scripts/build-android.sh"))
    outputs.dir(layout.buildDirectory.dir("generated/jniLibs"))
}

if (android.defaultConfig.versionName?.startsWith("0.0.5") == true && !v2UploaderEnabled) {
    throw GradleException("Android 0.0.5 release requires the Rust v2 uploader")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateCluetoothCoreBindings)
}
tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(buildCluetoothCoreAndroid)
}

val prepareCluetoothCoreAndroid by tasks.registering {
    group = "rust"
    description = "Generate UniFFI bindings and all four API-24 Android native libraries"
    dependsOn(generateCluetoothCoreBindings, buildCluetoothCoreAndroid)
}

val assembleCluetoothCoreDebug by tasks.registering {
    group = "verification"
    description = "Build the ABI-split debug app and instrumentation APKs from clean inputs"
    dependsOn("assembleDebug", "assembleDebugAndroidTest")
}

val connectedCluetoothCoreSmokeTest by tasks.registering {
    group = "verification"
    description = "Run the network-free UniFFI/Parquet smoke test on a connected device"
    dependsOn("connectedDebugAndroidTest")
}

dependencies {

    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-installations")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Work Manager
    implementation(libs.androidx.work.runtime.ktx)

    // Location Services
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Permissions (using compose built-in for now)
    // implementation(libs.accompanist.permissions)

    // Compression - using AAR format for Android
    implementation("com.github.luben:zstd-jni:1.5.5-5@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.5-5")

    // Encryption with libsodium
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // Navigation
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
