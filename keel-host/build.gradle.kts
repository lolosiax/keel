plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.classpath")
    // Host 主动引入 ABI 静态库；插件工程由 moe.lolosia.keel.plugin 自动带
    id("moe.lolosia.keel.abi")
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

keelClasspath {
    target("mingwX64")
}

kotlin {
    mingwX64 {
        binaries.executable {
            baseName = "keel-host"
            entryPoint = "moe.lolosia.keel.host.main"
        }
    }
    linuxArm64 {
        binaries.executable {
            baseName = "keel-host"
            entryPoint = "moe.lolosia.keel.host.main"
        }
    }
    linuxX64 {
        binaries.executable {
            baseName = "keel-host"
            entryPoint = "moe.lolosia.keel.host.main"
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            // 独立构建时从 Maven 取；在根 composite 下由本地构建替换
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            api("moe.lolosia.keel:keel-classpath-full:${libs.versions.keel.get()}")
        }
    }
}
