plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keelPlugin {
    // 当前开发机先只打 mingwX64；Linux 目标在对应平台或 CI 上打
    target("mingwX64")
}

kotlin {
    // 插件以动态库交付；launcher 发行版另行静态并入，不改变插件产物形态
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-hello" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-hello" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-hello" } }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}
