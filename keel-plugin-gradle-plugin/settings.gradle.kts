pluginManagement {
    // 本插件运行期要 apply `moe.lolosia.keel.abi` 与 `moe.lolosia.keel.logger`
    includeBuild("../keel-abi-gradle-plugin")
    includeBuild("../keel-logger-gradle-plugin")
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
        // 本地开发：先 publishAllToMavenLocal，再单独构建本目录
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-plugin-gradle-plugin"
