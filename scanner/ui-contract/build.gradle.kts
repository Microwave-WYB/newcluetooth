import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :ui-contract (U0, docs/v2-api.md §5.3): the types more than one UI lane uses; the only way C, D and E talk.
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
    testImplementation(libs.junit)
}
