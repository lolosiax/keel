plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        nativeMain.dependencies {
            // ORM 只依赖插件契约与数据库门面；驱动与方言都在这层之下
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            api("moe.lolosia.keel:keel-database-api:${libs.versions.keel.get()}")
            // JsonElement 列映射会出现在公开 API（OrmTypes.JSON）里
            api(libs.kotlinx.serialization.json)
        }
    }
}
