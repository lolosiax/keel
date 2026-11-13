import moe.lolosia.keel.gradle.classpath.KeelStripTask
import org.gradle.api.Action
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("moe.lolosia.keel.classpath")
    id("moe.lolosia.keel.logger")
    // launcher 静态并入 Host，也必须带上 ABI 的 C 静态库
    id("moe.lolosia.keel.abi")
    `maven-publish`
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

apply(from = "../gradle/publishing.gradle.kts")

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

// ——— 预编译 launcher 发行包 ———
// `moe.lolosia.keel.plugin` 的单 exe 产出按 target / buildType 解析这里的包：
// DEBUG → keel-launcher-dev-<target>，RELEASE → keel-launcher-<target>。
val launcherTargets = listOf("mingwX64", "linuxArm64", "linuxX64")

fun registerLauncherPackage(target: String, release: Boolean): TaskProvider<Zip> {
    val suffix = target.replaceFirstChar { it.uppercaseChar() }
    val kind = if (release) "release" else "debug"
    val taskName = "keelLauncherPackage" + (if (release) "Release" else "") + suffix
    val fileName = if (target.startsWith("mingw")) "keel.exe" else "keel"

    // strip 任务要在 Zip 的配置块之外注册（Gradle 9 不允许嵌套 register）
    val stripTask = if (release) {
        tasks.register(
            "keelLauncherStrip$suffix",
            KeelStripTask::class.java,
            Action<KeelStripTask> {
                group = "keel"
                description = "去掉 $target release launcher 的符号表"
                dependsOn("link${kind.replaceFirstChar { it.uppercaseChar() }}Executable$suffix")
                inputFile.set(layout.buildDirectory.file("bin/$target/releaseExecutable/$fileName"))
                this.target.set(target)
                outputDirectory.set(layout.buildDirectory.dir("generated/keel/launcher-strip/$target"))
            },
        )
    } else {
        null
    }

    return tasks.register(taskName, Zip::class.java) {
        group = "keel"
        description = "打包 $target 的 $kind launcher，供单 exe 注入"
        dependsOn("link${kind.replaceFirstChar { it.uppercaseChar() }}Executable$suffix")
        if (stripTask != null) {
            // K/N 的 release 产物仍带符号表；打包前 strip，注入后不会留下失效指针
            dependsOn(stripTask)
            from(stripTask.flatMap { it.outputDirectory }) { include("keel*") }
        } else {
            from(layout.buildDirectory.dir("bin/$target/debugExecutable")) { include("keel*") }
        }
        archiveFileName.set(
            if (release) "keel-launcher-$target.zip" else "keel-launcher-dev-$target.zip"
        )
        destinationDirectory.set(layout.buildDirectory.dir("keel"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val launcherPackages = launcherTargets.associateWith { target ->
    registerLauncherPackage(target, release = false)
}
val launcherReleasePackages = launcherTargets.associateWith { target ->
    registerLauncherPackage(target, release = true)
}

publishing {
    publications {
        launcherTargets.forEach { target ->
            val suffix = target.replaceFirstChar { it.uppercaseChar() }
            create<MavenPublication>("launcher$suffix") {
                artifactId = "keel-launcher-dev-$target"
                artifact(launcherPackages.getValue(target))
            }
            create<MavenPublication>("launcherRelease$suffix") {
                artifactId = "keel-launcher-$target"
                artifact(launcherReleasePackages.getValue(target))
            }
        }
    }
}
