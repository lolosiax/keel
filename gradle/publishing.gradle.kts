import java.util.Properties
import org.gradle.api.publish.PublishingExtension

/**
 * 所有 `moe.lolosia.keel:*` 工件的统一发布仓库配置。
 *
 * - `publishToMavenLocal` 不需要任何配置，产物落到本机 `~/.m2`，独立仓库用 `mavenLocal()` 消费；
 * - 团队 / CI 发布时在 `keel/local.properties`（或 `gradle.properties`）里配置：
 *
 * ```
 * keel.repo=http://<maven-host>/repository/keel
 * keel.username=<user>
 * keel.password=<password>
 * ```
 *
 * 凭据不入库。地址为空时只保留 mavenLocal 这一条路径，`publish` 会失败得明确。
 */

val repoProperties = Properties().apply {
    val file = rootProject.file("../local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun repoValue(key: String): String =
    (rootProject.findProperty(key) as String?) ?: repoProperties.getProperty(key).orEmpty()

extensions.configure<PublishingExtension> {
    repositories {
        val repoUrl = repoValue("keel.repo")
        if (repoUrl.isBlank()) return@repositories

        maven {
            name = "keel"
            setUrl(repoUrl)
            isAllowInsecureProtocol = true
            val username = repoValue("keel.username")
            val password = repoValue("keel.password")
            if (username.isNotBlank() || password.isNotBlank()) {
                credentials {
                    this.username = username
                    this.password = password
                }
            }
        }
    }
}
