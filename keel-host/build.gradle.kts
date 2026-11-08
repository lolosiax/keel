plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.classpath")
    // Host 主动引入 ABI 静态库；插件工程由 moe.lolosia.keel.plugin 自动带
    id("moe.lolosia.keel.abi")
    // Host 自身代码也要带日志调用点
    id("moe.lolosia.keel.logger")
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

// 构建期注入版本 / 提交 / 工具链，启动横幅与 --inspect 都从这里取
val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = buildInfoDir
    val keelVersion = libs.versions.keel.get()
    val kotlinVersion = libs.versions.kotlin.get()
    val root = projectDir
    outputs.dir(outputDir)
    doLast {
        fun git(vararg args: String): String = runCatching {
            providers.exec {
                workingDir = root
                commandLine("git", *args)
            }.standardOutput.asText.get().trim()
        }.getOrDefault("")

        val sha = git("rev-parse", "--short", "HEAD").ifBlank { "unknown" }
        val commitTime = git("log", "-1", "--format=%cI").ifBlank { "unknown" }
        val buildId = "$keelVersion-$sha"

        val file = outputDir.get().file("moe/lolosia/keel/host/build/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package moe.lolosia.keel.host.build

            /** 由 Gradle `generateBuildInfo` 注入；不要手改。 */
            internal object BuildInfo {
                const val VERSION = "$keelVersion"
                const val GIT_SHA = "$sha"
                const val COMMIT_TIME = "$commitTime"
                const val KOTLIN_VERSION = "$kotlinVersion"
                const val BUILD_ID = "$buildId"
            }
            """.trimIndent() + "\n",
        )
    }
}

keelClasspath {
    target("mingwX64")

    windows {
        productName = "Keel Modular Web Framework"
    }
}

// 能力加载器需要认识 database 的配置结构（KeelDbConfig）与能力描述符；
// 业务插件侧的同一份头文件由 keel-database-api 的 cinterop 读取。
val databaseApiIncludeDir = file("../keel-database-api/src/nativeCpp")
val keelAbiIncludeDir = file("../keel-abi/src/nativeCpp")
val databaseDef = file("src/nativeInterop/cinterop/keeldatabase.def")

kotlin {
    mingwX64 {
        compilations.getByName("main").cinterops.create("keeldatabase") {
            defFile = databaseDef
            includeDirs(databaseApiIncludeDir, keelAbiIncludeDir)
        }
        binaries.executable {
            baseName = "keel-host"
            entryPoint = "moe.lolosia.keel.host.main"
        }
    }
    linuxArm64 {
        compilations.getByName("main").cinterops.create("keeldatabase") {
            defFile = databaseDef
            includeDirs(databaseApiIncludeDir, keelAbiIncludeDir)
        }
        binaries.executable {
            baseName = "keel-host"
            entryPoint = "moe.lolosia.keel.host.main"
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("keeldatabase") {
            defFile = databaseDef
            includeDirs(databaseApiIncludeDir, keelAbiIncludeDir)
        }
        binaries.executable {
            baseName = "keel-host"
            entryPoint = "moe.lolosia.keel.host.main"
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            // 独立构建时从 Maven 取；在根 composite 下由本地构建替换
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            api("moe.lolosia.keel:keel-classpath-full:${libs.versions.keel.get()}")
            // Host 独占 HTTP 引擎；插件不依赖 Ktor
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            // YAML 只在 Host 侧解析；写回用原文本编辑，插件不依赖 KAML
            implementation(libs.kaml)
        }
    }
}
