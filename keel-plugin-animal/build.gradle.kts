import com.google.devtools.ksp.gradle.KspExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keelPlugin {
    target("mingwX64")
    // 固定产物名：另一个独立构建要按路径直接取这个包
    packageName = "keel-plugin-animal.keel"
    // 联调本仓库的 Host 源码：直接用本地 debug Host，并带上它的位置表
    runHostExecutable = "../keel-host/build/bin/mingwX64/debugExecutable/keel-host.exe"
    runHostDebugDir = "../keel-host/build/keel-debug"
}

// api 工件由 impl 侧 KSP 生成到 api/src/commonMain/kotlin（V1 提交制，产物随仓库提交）
extensions.configure<KspExtension> {
    arg("keel.api.dir", file("api/src/commonMain/kotlin").absolutePath)
}

// api 子工程的编译入口依赖这个任务：三个 target 产物相同，跑 host 目标即可
tasks.register("keelApiGenerate") {
    group = "keel"
    description = "把 @PublicApi 的代理 / Key / 访问器写入 api/src/commonMain/kotlin"
    dependsOn("kspKotlinMingwX64")
}

kotlin {
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-animal" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-animal" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-animal" } }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}
