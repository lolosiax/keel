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
    // 统一的 keel { } 根扩展与各 section 类型
    implementation("moe.lolosia.keel.toolchain:keel-gradle-common:${libs.versions.keel.get()}")
    // 需要 KMP / Native 的 Gradle 类型与 KotlinCompilerPluginSupportPlugin
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("keelLogger") {
            id = "moe.lolosia.keel.logger"
            implementationClass = "moe.lolosia.keel.gradle.logger.KeelLoggerGradlePlugin"
        }
    }
}

// getPluginArtifact() 需要在运行期知道编译器插件版本。
// composite 下插件可能直接以 classes 目录加载，manifest 不可靠，所以同时写一份资源。
val loggerVersionDir = layout.buildDirectory.dir("generated/keel-logger-version")
val loggerVersion = tasks.register("keelLoggerVersion") {
    val outputDir = loggerVersionDir
    val version = project.version.toString()
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("META-INF/keel-logger-version.txt").asFile
        file.parentFile.mkdirs()
        file.writeText("$version\n")
    }
}

sourceSets.main {
    resources.srcDir(loggerVersionDir)
}

tasks.named("processResources") {
    dependsOn(loggerVersion)
}

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version
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
