import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core-fake (lane A, docs/v2-api.md §6.2): a deterministic port of design/ui-v2/data.js behind the :core-api
// interfaces, with a live simulator. Only :gallery and tests depend on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    api(project(":core-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
