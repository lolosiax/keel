plugins {
    id("java")
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "moe.lolosia.keel"
version = "0.1.10"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 本地调试：-Pkeel.idea.localPlatform="C:\...\IntelliJ IDEA"
        // 默认仍按社区版 2025.2.6 构建，避免把本机安装路径写进仓库。
        val localPlatform = providers.gradleProperty("keel.idea.localPlatform").orNull
        if (localPlatform != null) {
            local(localPlatform)
        } else {
            // 2025.3 起统一发行版改用 intellijIdea("2025.3")，社区版 2025.2.x 仍用 legacy helper。
            intellijIdeaCommunity("2025.2.6")
        }
        // 使用 IDEA 自带的 Gradle 运行面板 / Build 面板来执行 keelPrepareRun
        bundledPlugin("com.intellij.gradle")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "moe.lolosia.keel.idea"
        name = "Keel"
        version = project.version.toString()
        description = """
            <p>识别工作区里的 Keel 插件工程，并把构建与运行拆成 IDEA 的运行配置。</p>
            <p>运行阶段直接启动 Host，避免 Gradle exec 把 stdout 接到管道后丢失 ANSI 颜色。</p>
        """.trimIndent()
        vendor {
            name = "lolosia"
        }
    }
}
