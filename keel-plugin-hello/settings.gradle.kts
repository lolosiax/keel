pluginManagement {
    includeBuild("../keel-plugin-gradle-plugin")
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
        // 本地开发：keel 根构建执行 publishAllToMavenLocal 后即可离线消费
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
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

rootProject.name = "keel-plugin-hello"
