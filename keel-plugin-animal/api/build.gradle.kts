plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

apply(from = "../../gradle/publishing.gradle.kts")

kotlin {
    // 与 keel-api 一致的目标集合：klib 要被各插件在这三个目标上链接。
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}

// api 源码由 impl 侧 KSP 生成（V1 提交制）：构建 impl 后产物落在 src/commonMain，随仓库提交
tasks.matching {
    it.name.startsWith("compileKotlin") || it.name.endsWith("KotlinMetadata")
}.configureEach {
    dependsOn(rootProject.tasks.matching { it.name == "keelApiGenerate" })
}
