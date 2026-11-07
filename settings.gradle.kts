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
        // 阿里云镜像对部分 KMP / Native 产物的同步不全，命中不到时回退 mavenCentral
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "keel"

// 每个顶层 `keel-*` 目录都是独立的 Gradle 构建，由根构建以 composite build 的方式聚合。
// 单独构建某个部分时：gradlew -p <目录> <任务>
includeBuild("keel-classpath-gradle-plugin")
includeBuild("keel-plugin-gradle-plugin")
includeBuild("keel-publish-gradle-plugin")
includeBuild("keel-router-ksp")
includeBuild("keel-classpath")
includeBuild("keel-classpath-full")
includeBuild("keel-api")
includeBuild("keel-host")
includeBuild("keel-launcher")
includeBuild("keel-plugin-hello")
