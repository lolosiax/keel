pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // 本地开发：根构建 publishAllToMavenLocal 后可单独构建本目录
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
                val username = properties.getProperty("keel.username").orEmpty()
                val password = properties.getProperty("keel.password").orEmpty()
                if (username.isNotBlank() || password.isNotBlank()) {
                    credentials {
                        this.username = username
                        this.password = password
                    }
                }
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-database-api"
