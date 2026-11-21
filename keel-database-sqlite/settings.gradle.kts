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
        // 第三方原生库（sqlite 的 include + libsqlite3.a）从本机 / 自建仓库解析
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "keel-database-sqlite"

// 仓库内开发：把生成侧纳入 composite，`build` 会自动跑 sqlite 的交叉编译与 staging；
// 单独 clone 本目录（sparse checkout）时没有兄弟目录，退回 Maven 工件。
val thirdpartyDir = file("../keel-thirdparty")
if (thirdpartyDir.isDirectory) {
    includeBuild(thirdpartyDir)
}
