plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            api("moe.lolosia.keel:keel-classpath:${libs.versions.keel.get()}")
        }
    }
}
