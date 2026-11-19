import com.google.devtools.ksp.gradle.KspExtension
import moe.lolosia.keel.gradle.common.*

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keel {
    plugin {
        // 与 hello 一致：当前开发机先打 mingwX64；Linux 目标在对应平台或 CI 上打
        target("mingwX64")
        // 插件版本与 keel 版本不是一回事，必须显式指定
        keelVersion = libs.versions.keel.get()
        // plugin.json 声明 requires: ["database"]，本地联调时把能力包一起放进运行目录
        runPlugin("moe.lolosia.keel:keel-database-mariadb:${libs.versions.keel.get()}@keel")
        // 联调本仓库的 Host 源码：直接用本地 debug Host（mavenLocal 里的发布版可能落后于 share 协议）
        runHostExecutable = "../keel-host/build/bin/mingwX64/debugExecutable/keel-host.exe"
        runHostDebugDir = "../keel-host/build/keel-debug"
    }
}

// api 工件由 impl 侧 KSP 生成到 api/src/commonMain/kotlin（V1 提交制，产物随仓库提交）
extensions.configure<KspExtension> {
    arg("keel.api.dir", file("api/src/commonMain/kotlin").absolutePath)
}

// api 子工程的编译入口依赖这个任务：三个 target 产物相同，跑 host 目标即可
tasks.register("keelApiGenerate") {
    group = "keel"
    description = "把 @PublicApi 的镜像 / 代理 / Key / 访问器写入 api/src/commonMain/kotlin"
    dependsOn("kspKotlinMingwX64")
}

// V1 提交制：生成器直接改写 api/src/commonMain，产物必须随仓库提交；过期时这里失败
tasks.register("keelApiCheck") {
    group = "keel"
    description = "确认 api 生成物与 @PublicApi 声明一致（V1 提交制）"
    dependsOn("keelApiGenerate")
    doLast {
        val status = providers.exec {
            commandLine("git", "status", "--porcelain", "--", "api/src/commonMain")
        }.standardOutput.asText.get()
        check(status.isBlank()) {
            "api 生成物已过期，请提交 api/src/commonMain 的改动：\n$status"
        }
    }
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
            implementation(libs.kotlinx.datetime)
        }
        nativeMain.dependencies {
            // 通过 C ABI 查库：驱动由 database 能力包提供，插件只依赖这份门面
            api("moe.lolosia.keel:keel-database-api:${libs.versions.keel.get()}")
        }
    }
}
