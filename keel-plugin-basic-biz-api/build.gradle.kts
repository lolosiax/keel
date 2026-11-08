plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

kotlin {
    // 与 keel-api 一致的目标集合：klib 要被各插件在这三个目标上链接。
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}
