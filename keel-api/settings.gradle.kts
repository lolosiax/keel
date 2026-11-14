pluginManagement {
    // 日志改写插件同样要作用于本模块：`Log.site()` 在 keel-api 内部也要能拿到调用点
    includeBuild("../keel-logger-gradle-plugin")
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // 编译器插件工件：本地开发先跑 publishAllToMavenLocal
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        // 自建 Maven（地址来自 ../local.properties 的 keel.repo；不入库）
        val repoFile = java.io.File(settingsDir, "../local.properties")
        val properties = java.util.Properties().apply {
            if (repoFile.isFile) repoFile.inputStream().use { load(it) }
        }
        val repoUrl = properties.getProperty("keel.repo").orEmpty()
        if (repoUrl.isNotBlank()) {
            maven {
                url = uri(repoUrl)
                isAllowInsecureProtocol = true
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-api"
