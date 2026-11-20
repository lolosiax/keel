import java.util.*

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

group = "moe.lolosia.keel.toolchain"
version = libs.versions.keel.get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 只放 DSL 类型，不依赖 Kotlin MPP / Native 的 Gradle 类型
    implementation(gradleApi())
}

// 发布仓库：地址与账号来自 <repo>/local.properties 的 keel.repo / keel.username / keel.password（不入库）。
val localProperties = Properties().apply {
    val file = rootProject.file("../local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun configValue(key: String): String? =
    (rootProject.findProperty(key) as String?) ?: localProperties.getProperty(key)

publishing {
    // 只应用 maven-publish 不会自动建 publication；这里显式发布 jar，
    // 否则别的仓库从 mavenLocal 解析 keel-plugin-gradle-plugin 时会缺这个依赖。
    publications {
        create<org.gradle.api.publish.maven.MavenPublication>("maven") {
            from(components["java"])
        }
    }
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
