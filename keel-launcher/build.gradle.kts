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
// `moe.lolosia.keel.plugin` 的单 exe 产出按 target 解析这里的 debug 包，
// 把 keel-bundle.zip 注入 launcher 副本；release Host 修复前只发布 debug。
val launcherTargets = listOf("mingwX64", "linuxArm64", "linuxX64")

val launcherPackages = launcherTargets.associateWith { target ->
    val suffix = target.replaceFirstChar { it.uppercaseChar() }
    tasks.register<Zip>("keelLauncherPackage$suffix") {
        group = "keel"
        description = "打包 $target 的 debug launcher，供单 exe 注入"
        dependsOn("linkDebugExecutable$suffix")
        from(layout.buildDirectory.dir("bin/$target/debugExecutable")) { include("keel*") }
        archiveFileName.set("keel-launcher-dev-$target.zip")
        destinationDirectory.set(layout.buildDirectory.dir("keel"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

publishing {
    publications {
        launcherPackages.forEach { (target, packageTask) ->
            val suffix = target.replaceFirstChar { it.uppercaseChar() }
            create<MavenPublication>("launcher$suffix") {
                artifactId = "keel-launcher-dev-$target"
                artifact(packageTask)
            }
        }
    }
}
