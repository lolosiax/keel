import java.util.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.lolosia.keel.toolchain"
version = libs.versions.keel.get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 编译器在加载插件 jar 时提供这些类型；不能打进插件，否则与 K/N 编译器冲突
    compileOnly(libs.kotlin.compiler.embeddable)
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
