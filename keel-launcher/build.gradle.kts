plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("moe.lolosia.keel.classpath")
    id("moe.lolosia.keel.logger")
    // launcher 静态并入 Host，也必须带上 ABI 的 C 静态库
    id("moe.lolosia.keel.abi")
}

// 发行入口没有自己的内嵌资源，只借用插件的 Windows 版本信息生成。
keelClasspath {
    target("mingwX64")
    embedClasspath = false

    windows {
        productName = "Keel Modular Web Framework"
    }
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
