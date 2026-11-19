pluginManagement {
    // 生成侧复用消费插件的 manifest 门禁 / 声明 / 打包任务
    includeBuild("../keel-thirdparty-gradle-plugin")
    includeBuild("../keel-gradle-common")
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-thirdparty"
