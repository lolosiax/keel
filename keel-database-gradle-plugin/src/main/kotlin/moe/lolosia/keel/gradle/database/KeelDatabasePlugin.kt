package moe.lolosia.keel.gradle.database

import com.google.devtools.ksp.gradle.KspExtension
import moe.lolosia.keel.gradle.common.KeelExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * 数据库 / ORM 接线插件。
 *
 * 应用后：
 * - 注册 `keel { database { dialect = ... } }`；
 * - 把 `keel-orm-ksp` 加到每个 native 目标的 `ksp<target>` 配置；
 * - 给 `nativeMain` 加 `keel-orm` 与 `keel-database-api`；
 * - 通过 KSP 参数下发 `keel.orm.dialect`（生成的 SQL 用哪个方言）与
 *   `keel.orm.enabled=true`（`keel-plugin-ksp` 据此在 Worker 入口调用 `OrmMetaBootstrap.install()`）。
 *
 * 不编译驱动：`keel-database-sqlite` / `keel-database-mariadb` 是运行期的 `.keel` 能力包。
 */
class KeelDatabasePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = KeelExtension.of(project).register(KeelDatabaseExtension())
        // 兼容旧写法：keelDatabase { } 与 keel { database { } } 是同一个对象
        project.extensions.add("keelDatabase", extension)

        project.plugins.withId("com.google.devtools.ksp") {
            val ksp = project.extensions.getByType(KspExtension::class.java)
            // 用 provider 延迟取值：build script 里的 keel { } 块在 apply 之后才执行
            ksp.arg(DIALECT_OPTION, project.provider { extension.dialect })
            ksp.arg(ORM_ENABLED_OPTION, project.provider { "true" })
            ksp.arg(ORM_DIR_OPTION, project.provider { project.file(extension.outputDir).absolutePath })
        }

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            // KSP 在自己的 afterEvaluate 里创建 ksp<target> 配置并固定处理器 classpath
            project.configurations.configureEach { configuration ->
                if (!extension.enabled) return@configureEach
                if (!isNativeKspConfiguration(project, configuration.name)) return@configureEach
                val version = extension.keelVersion ?: project.version.toString()
                project.dependencies.add(
                    configuration.name,
                    "moe.lolosia.keel.toolchain:keel-orm-ksp:$version",
                )
            }

            project.afterEvaluate {
                if (!extension.enabled) return@afterEvaluate
                validateDialect(extension.dialect)
                val version = extension.keelVersion ?: project.version.toString()
                val configuration = project.configurations.findByName("nativeMainImplementation")
                if (configuration == null) {
                    project.logger.warn(
                        "未找到 nativeMainImplementation，跳过自动添加 keel-orm / keel-database-api"
                    )
                } else {
                    project.dependencies.add(configuration.name, "moe.lolosia.keel:keel-orm:$version")
                    project.dependencies.add(
                        configuration.name,
                        "moe.lolosia.keel:keel-database-api:$version",
                    )
                }
            }
        }
    }

    /** `ksp<target>` 且确实对应一个 native 目标（排除 `kspCommonMainMetadata` / `kspTest*`）。 */
    private fun isNativeKspConfiguration(project: Project, name: String): Boolean {
        if (!name.startsWith("ksp")) return false
        val targetName = name.removePrefix("ksp").replaceFirstChar { it.lowercaseChar() }
        if (targetName.isEmpty()) return false
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            ?: return false
        return kotlin.targets.findByName(targetName) is KotlinNativeTarget
    }

    private fun validateDialect(dialect: String) {
        val normalized = dialect.trim().lowercase()
        if (normalized !in SUPPORTED_DIALECTS) {
            throw GradleException(
                "未知的 keel.database.dialect=$dialect；当前支持 ${SUPPORTED_DIALECTS.joinToString(" / ")}"
            )
        }
    }

    private companion object {
        const val DIALECT_OPTION = "keel.orm.dialect"
        const val ORM_ENABLED_OPTION = "keel.orm.enabled"
        const val ORM_DIR_OPTION = "keel.orm.dir"
        val SUPPORTED_DIALECTS = setOf("sqlite", "mariadb", "mysql")
    }
}
