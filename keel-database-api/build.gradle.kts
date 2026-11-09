plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

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
        // 函数指针表的解引用放在目标源集：KMP 的元数据编译会把 commonized cinterop 结构体
        // 降级成不透明类型（字段全丢），所以 nativeMain 只保留 expect 声明。
        // 同一份 actual 文件通过 srcDir 被三个目标共享，避免写三遍。
        val bridgeDir = "src/nativeCinteropMain/kotlin"
        getByName("mingwX64Main").kotlin.srcDir(bridgeDir)
        getByName("linuxArm64Main").kotlin.srcDir(bridgeDir)
        getByName("linuxX64Main").kotlin.srcDir(bridgeDir)
    }
}
