plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

kotlin {
    mingwX64 {
        binaries.executable {
            baseName = "keel"
            entryPoint = "moe.lolosia.keel.launcher.main"
        }
    }
    linuxArm64 {
        binaries.executable {
            baseName = "keel"
            entryPoint = "moe.lolosia.keel.launcher.main"
        }
    }
    linuxX64 {
        binaries.executable {
            baseName = "keel"
            entryPoint = "moe.lolosia.keel.launcher.main"
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            // 单文件发行版：Host 以静态链接方式并入 launcher 可执行文件
            api("moe.lolosia.keel:keel-host:${libs.versions.keel.get()}")
        }
    }
}
