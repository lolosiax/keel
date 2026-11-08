plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

// ABI 头归本模块所有；keel_abi.h 仍由 keel-abi 提供，这里只读兄弟目录。
val abiIncludeDir = file("../keel-abi/src/nativeCpp")
val dbIncludeDir = file("src/nativeCpp")
val cinteropDef = file("src/nativeInterop/cinterop/keeldatabase.def")

kotlin {
    mingwX64 {
        compilations.getByName("main").cinterops.create("keeldatabase") {
            defFile = cinteropDef
            includeDirs(abiIncludeDir, dbIncludeDir)
        }
    }
    linuxArm64 {
        compilations.getByName("main").cinterops.create("keeldatabase") {
            defFile = cinteropDef
            includeDirs(abiIncludeDir, dbIncludeDir)
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("keeldatabase") {
            defFile = cinteropDef
            includeDirs(abiIncludeDir, dbIncludeDir)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        nativeMain.dependencies {
            // 读取 Host 下发的能力指针（PluginCapabilities），并复用 keel-api 的运行时类型
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}
