plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keelPlugin {
    target("mingwX64")
    // 联调本仓库的 Host 源码：直接用本地 debug Host，并带上它的位置表
    runHostExecutable = "../keel-host/build/bin/mingwX64/debugExecutable/keel-host.exe"
    runHostDebugDir = "../keel-host/build/keel-debug"
    // animal 是另一个独立构建：先构建它，把产物塞进同一个运行目录
    runPluginFile("../keel-plugin-animal/build/keel/keel-plugin-animal.keel")
}

kotlin {
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-dog" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-dog" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-dog" } }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            // Animal / Callable / AnimalPlugin 的镜像由声明方的 api 工件提供
            api("moe.lolosia.keel:keel-plugin-animal-api:${libs.versions.keel.get()}")
        }
    }
}
