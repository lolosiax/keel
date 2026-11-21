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

// 单 exe 产出复用 keel-classpath-gradle-plugin 的 Windows 资源 helper
includeBuild("../keel-classpath-gradle-plugin")
// 统一的 keel { } 根扩展：单独构建时从兄弟目录取，不必先发布
includeBuild("../keel-gradle-common")
// requires: ["database"] 时自动 apply 数据库接线插件（实现依赖）
includeBuild("../keel-database-gradle-plugin")

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
