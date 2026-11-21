pluginManagement {
    includeBuild("../keel-plugin-gradle-plugin")
    includeBuild("../keel-abi-gradle-plugin")
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
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

rootProject.name = "keel-plugin-basic-biz"

// 仓库内开发：把第三方原生库的生成侧纳入 composite，bcrypt 的交叉编译与 staging
// 会随本构建的 `build` 自动跑；单独 clone 本目录时没有兄弟目录，退回 Maven 工件。
val thirdpartyDir = file("../keel-thirdparty")
if (thirdpartyDir.isDirectory) {
    includeBuild(thirdpartyDir)
}

// api 工件与插件同属一个构建：目录叫 api，工程名保持发布坐标 `keel-plugin-basic-biz-api`
include(":keel-plugin-basic-biz-api")
project(":keel-plugin-basic-biz-api").projectDir = file("api")
