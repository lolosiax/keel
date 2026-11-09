plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

// 发布仓库统一走 ../gradle/publishing.gradle.kts；本地开发用 publishToMavenLocal
apply(from = "../gradle/publishing.gradle.kts")

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
