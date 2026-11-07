package moe.lolosia.keel.gradle.publish

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension

/**
 * 把插件的构建产物发布到自建 Maven。
 *
 * 使用方只需在 `build.gradle.kts` 里：
 *
 * ```
 * plugins { id("moe.lolosia.keel.publish") }
 * keelPublish {
 *     url = "http://<maven-host>/repository/keel"
 *     username = "..."
 *     password = "..."
 * }
 * ```
 *
 * 地址与账号也可由 `keel.repo` / `keel.username` / `keel.password` 提供，
 * 建议放在 `local.properties`（已被 `.gitignore` 忽略）。
 *
 * DEMO 阶段只负责仓库注册；产物打包（按目标归档原生库与资源）在 P0 补齐。
 */
class KeelPublishPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")

        val extension = project.extensions.create("keelPublish", KeelPublishExtension::class.java)
        extension.url = project.findProperty("keel.repo") as String? ?: ""
        extension.username = project.findProperty("keel.username") as String? ?: ""
        extension.password = project.findProperty("keel.password") as String? ?: ""

        project.afterEvaluate {
            val publishing = project.extensions.getByType(PublishingExtension::class.java)
            if (extension.url.isBlank()) {
                project.logger.warn(
                    "keelPublish.url 为空，未注册发布仓库；" +
                        "请在 local.properties 配置 keel.repo（可选 keel.username / keel.password）"
                )
                return@afterEvaluate
            }
            publishing.repositories.maven { repo ->
                repo.url = project.uri(extension.url)
                repo.isAllowInsecureProtocol = true
                if (extension.username.isNotBlank() || extension.password.isNotBlank()) {
                    repo.credentials { credentials ->
                        credentials.username = extension.username
                        credentials.password = extension.password
                    }
                }
            }
        }
    }
}
