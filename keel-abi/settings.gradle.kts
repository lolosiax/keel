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
        // zstd 解压库（独立 clone 时按 Maven 坐标解析）
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-abi"

// 仓库内开发：zstd 由 keel-thirdparty 从上游源码编译并 staging；
// 单独 clone 本目录（sparse checkout）时退回已发布的 Maven 工件。
val thirdpartyDir = file("../keel-thirdparty")
if (thirdpartyDir.isDirectory) {
    includeBuild(thirdpartyDir)
}
