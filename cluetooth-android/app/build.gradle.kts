import com.google.gms.googleservices.GoogleServicesTask
import groovy.json.JsonSlurper
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.gms.google-services") apply false
}

// Host checks compile/test real app code without creating Firebase resources or an APK.
val hostCheckFlag = providers.gradleProperty("cluetoothHostChecks").orNull
if (hostCheckFlag != null) {
    val allowed = setOf(":app:testDebugUnitTest", ":app:lintDebug")
    val requested = gradle.startParameter.taskNames
    if (hostCheckFlag != "true" || requested.isEmpty() || requested.any { it !in allowed }) {
        throw GradleException(
            "cluetoothHostChecks requires literal true and only explicit " +
                ":app:testDebugUnitTest or :app:lintDebug selectors"
        )
    }
} else {
    apply(plugin = "com.google.gms.google-services")
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
        abortOnError = true
    }
}

// Process only Firebase metadata: no app compilation, native build, APK or network calls.
if (hostCheckFlag == null) {
    tasks.register("verifyFirebaseConfigs") {
        group = "verification"
        description = "Verify genuine debug/test-bucket and release/production Firebase resources"
        dependsOn("processDebugGoogleServices", "processReleaseGoogleServices")
        doLast {
            for (variant in listOf("Debug", "Release")) {
                val debug = variant == "Debug"
                val packageName = "edu.ucsd.sysnet.cluetoothscanner" + if (debug) ".debug" else ""
                val expected = mapOf(
                    "google_app_id" to if (debug) "1:952828187654:android:24c3c17e0c06adbeb81f87"
                        else "1:952828187654:android:9a76ce60006c562cb81f87",
                    "google_storage_bucket" to if (debug) "cluetooth-1da02-debug"
                        else "cluetooth-1da02.firebasestorage.app",
                    "project_id" to "cluetooth-1da02",
                    "gcm_defaultSenderId" to "952828187654",
                )
                val processing = tasks.named<GoogleServicesTask>("process${variant}GoogleServices").get()
                val configFile = file(if (debug) "src/debug/google-services.json" else "google-services.json")
                check(processing.applicationId.get() == packageName) { "$variant application ID mismatch" }
                check(processing.googleServicesJsonFiles.get().first { it.isFile } == configFile) {
                    "$variant selected an unexpected Firebase config"
                }
                val config = JsonSlurper().parse(configFile) as Map<*, *>
                val projectInfo = config["project_info"] as Map<*, *>
                val clientInfo = (config["client"] as List<*>).map {
                    (it as Map<*, *>)["client_info"] as Map<*, *>
                }.single { (it["android_client_info"] as Map<*, *>)["package_name"] == packageName }
                check(mapOf(
                    "google_app_id" to clientInfo["mobilesdk_app_id"],
                    "google_storage_bucket" to projectInfo["storage_bucket"],
                    "project_id" to projectInfo["project_id"],
                    "gcm_defaultSenderId" to projectInfo["project_number"],
                ) == expected) { "$variant Firebase config differs from the registered identity/bucket" }
                val xml = processing.outputDirectory.file("values/values.xml").get().asFile
                val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(xml).getElementsByTagName("string")
                val generated = (0 until strings.length).associate {
                    val node = strings.item(it)
                    node.attributes.getNamedItem("name").nodeValue to node.textContent
                }
                expected.forEach { (name, value) ->
                    check(generated[name] == value) { "$variant generated $name mismatch" }
                }
                logger.lifecycle("$variant Firebase verified: $packageName, ${expected["google_app_id"]}, ${expected["google_storage_bucket"]}")
            }
        }
    }
}

val cluetoothCoreDirectory = rootProject.layout.projectDirectory.dir("../cluetooth-core")
val cluetoothCoreInputs = fileTree(cluetoothCoreDirectory) {
    include("Cargo.toml", "Cargo.lock", "uniffi.toml")
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
    inputs.file(rootProject.layout.projectDirectory.file("../mise.toml"))
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
    inputs.file(rootProject.layout.projectDirectory.file("../mise.toml"))
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
    description = "Opt-in device smoke; app startup can schedule Firebase uploads"
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
