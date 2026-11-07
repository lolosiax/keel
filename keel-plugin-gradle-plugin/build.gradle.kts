import java.util.*

plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

group = "moe.lolosia.keel.toolchain"
version = libs.versions.keel.get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 需要 Kotlin MPP / Native 的 Gradle 类型来定位 sharedLib 产物
    implementation(libs.kotlin.gradle.plugin)
    // 插件工程自动带上 ABI 静态库注入；composite 下由 includeBuild 替换成本地工程
    implementation("moe.lolosia.keel.toolchain:keel-abi-gradle-plugin:${libs.versions.keel.get()}")
    // 插件工程自动 apply KSP，并接入 Worker 入口生成器
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    // 插件工程自动带日志调用改写
    implementation("moe.lolosia.keel.toolchain:keel-logger-gradle-plugin:${libs.versions.keel.get()}")
}

gradlePlugin {
    plugins {
        create("keelPluginPackaging") {
            id = "moe.lolosia.keel.plugin"
            implementationClass = "moe.lolosia.keel.gradle.plugin.KeelPluginPackagingPlugin"
        }
    }
}

// 发布仓库：地址与账号来自 <repo>/local.properties 的 keel.repo / keel.username / keel.password（不入库）。
val localProperties = Properties().apply {
    val file = rootProject.file("../local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun configValue(key: String): String? =
    (rootProject.findProperty(key) as String?) ?: localProperties.getProperty(key)

publishing {
    repositories {
        val repoUrl = configValue("keel.repo").orEmpty()
        if (repoUrl.isNotBlank()) {
            maven {
                url = uri(repoUrl)
                isAllowInsecureProtocol = true
                val repoUsername = configValue("keel.username").orEmpty()
                val repoPassword = configValue("keel.password").orEmpty()
                if (repoUsername.isNotBlank() || repoPassword.isNotBlank()) {
                    credentials {
                        username = repoUsername
                        password = repoPassword
                    }
                }
            }
        }
    }
}
