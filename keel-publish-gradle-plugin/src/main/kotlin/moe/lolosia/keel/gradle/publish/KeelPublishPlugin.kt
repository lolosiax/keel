package moe.lolosia.keel.gradle.publish

import moe.lolosia.keel.gradle.common.KeelExtension
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
 * keel {
 *     publish {
 *         url = "http://<maven-host>/repository/keel"
 *         username = "..."
 *         password = "..."
 *     }
 * }
 * ```
 *
 * 地址与账号也可由 `keel.repo` / `keel.username` / `keel.password` 提供，
 * 建议放在 `local.properties`（已被 `.gitignore` 忽略）。
 *
 * 只负责注册发布仓库；产物打包（按目标归档原生库与资源）不在本插件内。
 */
class KeelPublishPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")

        val extension = KeelExtension.of(project).register(KeelPublishExtension())
        // 兼容旧写法：keelPublish { } 与 keel { publish { } } 是同一个对象
        project.extensions.add("keelPublish", extension)
        // 没显式配置时从 -P / local.properties 读取
        if (extension.url.isBlank()) {
            extension.url = project.findProperty("keel.repo") as String? ?: ""
        }
        if (extension.username.isBlank()) {
            extension.username = project.findProperty("keel.username") as String? ?: ""
        }
        if (extension.password.isBlank()) {
            extension.password = project.findProperty("keel.password") as String? ?: ""
        }

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
