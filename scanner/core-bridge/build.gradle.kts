// :core-bridge (lane A, docs/v2-api.md §6.2): the UniFFI bindings of cluetooth-core's v2 `Core`, mapped 1:1 onto
// :core-api (CoreQueries, CoreStorage, CoreIngest). JVM tests go through the real bindings and the host library.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val cluetoothCoreDirectory = rootProject.layout.projectDirectory.dir("../cluetooth-core")
val uniffiDirectory = layout.buildDirectory.dir("generated/source/uniffi")
val jniLibsDirectory = layout.buildDirectory.dir("generated/jniLibs")
// arm64-v8a for devices, x86_64 for the emulator; override with -PcluetoothAbis=... or CLUETOOTH_ABIS
val coreAbis = providers.gradleProperty("cluetoothAbis")
    .orElse(providers.environmentVariable("CLUETOOTH_ABIS"))
    .orElse("arm64-v8a,x86_64")

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.corebridge"
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
    sourceSets {
        getByName("main") {
            java.srcDir(uniffiDirectory)
            jniLibs.srcDir(jniLibsDirectory)
        }
        // the instrumented smoke test reads the same bundle the JVM tests use
        getByName("androidTest") {
            assets.srcDir(rootProject.layout.projectDirectory.dir("core-fake/src/test/resources"))
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // the host cdylib built (with its UniFFI metadata) by generateCluetoothCoreBindings
            it.systemProperty("jna.library.path", cluetoothCoreDirectory.dir("target/release").asFile.absolutePath)
            it.systemProperty("cluetooth.fake.resources", rootProject.file("core-fake/src/test/resources").absolutePath)
        }
    }
    lint {
        abortOnError = true
    }
}

val generateCluetoothCoreBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build the host Rust library and generate the pinned UniFFI Kotlin bindings for :core-bridge"
    workingDir(cluetoothCoreDirectory)
    commandLine("bash", "scripts/generate-kotlin-bindings.sh", uniffiDirectory.get().asFile.absolutePath)
    inputs.files(fileTree(cluetoothCoreDirectory) {
        include("Cargo.toml", "Cargo.lock", "uniffi.toml")
        include("src/**/*.rs")
    })
    inputs.file(cluetoothCoreDirectory.file("scripts/generate-kotlin-bindings.sh"))
    outputs.dir(uniffiDirectory)
}

// Same script as the v3 :app (cargo-ndk 4.1.2, NDK 27.2, API 24); it builds the whole crate, v2 included.
val buildCluetoothCoreAndroid by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build the v2 core for ${'$'}{coreAbis.get()} (API 24) into build/generated/jniLibs"
    workingDir(cluetoothCoreDirectory)
    environment("CLUETOOTH_ABIS", coreAbis.get())
    inputs.property("cluetoothAbis", coreAbis)
    commandLine("bash", "scripts/build-android.sh", jniLibsDirectory.get().asFile.absolutePath)
    inputs.files(fileTree(cluetoothCoreDirectory) {
        include("Cargo.toml", "Cargo.lock", "uniffi.toml")
        include("src/**/*.rs")
    })
    inputs.file(rootProject.layout.projectDirectory.file("../mise.toml"))
    inputs.file(cluetoothCoreDirectory.file("scripts/build-android.sh"))
    outputs.dir(jniLibsDirectory)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildCluetoothCoreAndroid)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateCluetoothCoreBindings)
}

dependencies {
    api(project(":core-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // @RequiresApi in the generated Android bindings
    implementation("androidx.annotation:annotation:1.8.2")

    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    // the shared query contracts the fake also passes (lane A's own fake)
    testImplementation(project(":core-fake"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
