plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keelPlugin {
    // 与 hello 一致：当前开发机先打 mingwX64；Linux 目标在对应平台或 CI 上打
    target("mingwX64")
    // 插件版本与 keel 版本不是一回事，必须显式指定，否则会去解析 0.1.0-SNAPSHOT 以外的坐标
    keelVersion = libs.versions.keel.get()
}

kotlin {
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-basic-biz" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-basic-biz" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-basic-biz" } }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            api("moe.lolosia.keel:keel-plugin-basic-biz-api:${libs.versions.keel.get()}")
            api("moe.lolosia.keel:keel-plugin-rbac-api:${libs.versions.keel.get()}")
            implementation(libs.kotlinx.datetime)
        }
        nativeMain.dependencies {
            // 通过 C ABI 查库：驱动由 database 能力包提供，插件只依赖这份门面
            api("moe.lolosia.keel:keel-database-api:${libs.versions.keel.get()}")
        }
    }
}
