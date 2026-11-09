import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    id("moe.lolosia.keel.classpath")
    // Host 主动引入 ABI 静态库；插件工程由 moe.lolosia.keel.plugin 自动带
    id("moe.lolosia.keel.abi")
    // Host 自身代码也要带日志调用点
    id("moe.lolosia.keel.logger")
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

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

// ——— Host 可执行包 ———
// keelRun 按 moe.lolosia.keel:keel-host-<target>:<version>@zip 解析；
// 每个平台各自发布自己的 Host（Windows/mingwX64、Linux/arm64 等）。
val hostTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("win") -> "mingwX64"
        arch.contains("aarch64") || arch.contains("arm64") -> "linuxArm64"
        else -> "linuxX64"
    }
}

/**
 * 开发用 Host 包（**debug** 构建）。
 *
 * `keelRun` 用它做集成测试：启动快、符号全、崩溃报告能落到行号。
 * 生产部署的 release 可执行文件不走这个工件，用 `linkReleaseExecutable<target>` 单独构建。
 *
 * 当前 release Host 在插件 HTTP 响应路径上有 ACCESS_VIOLATION（debug 正常），
 * 在定位清楚前 `keelRun` 固定用 debug，避免开发循环被它挡住。
 */
val keelHostPackage = tasks.register<Zip>("keelHostPackage") {
    group = "keel"
    description = "打包当前宿主的 debug keel-host（$hostTarget），供 keelRun 从 Maven 解析"
    dependsOn("linkDebugExecutable${hostTarget.replaceFirstChar { it.uppercaseChar() }}")
    from(layout.buildDirectory.dir("bin/$hostTarget/debugExecutable")) { include("keel-host*") }
    archiveFileName.set("keel-host-dev-$hostTarget.zip")
    destinationDirectory.set(layout.buildDirectory.dir("keel"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("hostExecutable") {
            // 不能叫 keel-host-mingwX64：KMP 目标发布在同名目录（Windows 大小写不敏感）会撞车
            artifactId = "keel-host-dev-$hostTarget"
            artifact(keelHostPackage)
        }
    }
}
