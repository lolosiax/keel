pluginManagement {
    includeBuild("../keel-logger-gradle-plugin")
    includeBuild("../keel-abi-gradle-plugin")
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Ktor 的 native 工件只在 Central 全量同步，镜像可能缺 variant
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
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

rootProject.name = "keel-launcher"
