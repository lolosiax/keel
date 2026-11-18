import com.google.devtools.ksp.gradle.KspExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keelPlugin {
    // 与其它插件一致：开发机先打 mingwX64，Linux 目标由对应平台 / CI 打
    target("mingwX64")
    keelVersion = libs.versions.keel.get()
    // 默认实现依赖数据库能力包
    runPlugin("moe.lolosia.keel:keel-database-mariadb:${libs.versions.keel.get()}@keel")
}

// 契约（@PublicApi 接口 / DTO / 常量）写在 impl 的 commonMain，
// api 工件由 KSP 生成到 api/src/commonMain/kotlin（V1 提交制，产物随仓库提交）。
extensions.configure<KspExtension> {
    arg("keel.api.dir", file("api/src/commonMain/kotlin").absolutePath)
}

tasks.register("keelApiGenerate") {
    group = "keel"
    description = "把 @PublicApi 的镜像 / 代理 / Key / 访问器写入 api/src/commonMain/kotlin"
    dependsOn("kspKotlinMingwX64")
}

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
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-basic-rbac" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-basic-rbac" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-basic-rbac" } }

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
            // 查库走能力门面，驱动由 database 能力包提供
            api("moe.lolosia.keel:keel-database-api:${libs.versions.keel.get()}")
        }
    }
}
