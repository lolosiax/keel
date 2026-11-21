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
 * KSP2 只按 native 目标编译，不处理 commonMain / intermediate 源集；而 `nativeMain`
 * 参与 metadata 编译，直接看得到各目标的 KSP 输出是不可能的。所以这里让 Gradle 参与：
 *
 * 1. `keel-orm-ksp` 只挂到**一个** native 目标的 `ksp<target>` 配置（生成物与目标无关，
 *    没必要三份，也避免并发写同一目录）；
 * 2. 生成物写到 `build/generated/keel/orm`（默认，可用 [KeelDatabaseExtension.outputDir] 改），
 *    不进入 `src/`、不被 git 追踪；
 * 3. 把该目录挂进 `nativeMain` 源码集，并让 metadata / 各目标编译依赖那个 KSP 任务。
 *
 * 另外注册 `keel { database { dialect = ... } }`、给 `nativeMain` 加
 * `keel-orm` / `keel-database-api`，并通过 KSP 参数下发方言与 `keel.orm.enabled=true`
 * （`keel-plugin-ksp` 据此在 Worker 入口调用 `OrmMetaBootstrap.install()`）。
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
                val target = generationTarget(project) ?: return@configureEach
                if (configuration.name != "ksp${target.capitalized()}") return@configureEach
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

                val target = generationTarget(project) ?: return@afterEvaluate
                val generated = project.layout.buildDirectory.dir(GENERATED_DIR)
                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                kotlin.sourceSets.getByName("nativeMain").kotlin.srcDir(generated)

                // metadata 编译与其它目标看不到生成目标的 KSP 输出，必须显式排序
                val kspTask = "kspKotlin${target.capitalized()}"
                project.tasks.configureEach { task ->
                    val name = task.name
                    if (name == kspTask) return@configureEach
                    if (name == "compileNativeMainKotlinMetadata" || name.startsWith("compileKotlin")) {
                        task.dependsOn(kspTask)
                    }
                }
            }
        }
    }

    /**
     * 选一个 native 目标承载 ORM 生成。
     *
     * 生成物与目标无关，所以优先复用宿主目标（本机构建不会额外触发交叉编译），
     * 宿主目标不存在时退回第一个 native 目标。
     */
    private fun generationTarget(project: Project): String? {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            ?: return null
        val targets = kotlin.targets.withType(KotlinNativeTarget::class.java).map { it.name }
        if (targets.isEmpty()) return null
        val host = hostTargetName()
        return if (host in targets) host else targets.first()
    }

    /** KSP / Kotlin 任务名里的目标段首字母大写：`mingwX64` → `MingwX64`。 */
    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

    private fun hostTargetName(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("win") -> "mingwX64"
            arch.contains("aarch64") || arch.contains("arm64") -> "linuxArm64"
            else -> "linuxX64"
        }
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

        /** 生成物落点（相对 build 目录）；只在构建产物里，不进源码树。 */
        const val GENERATED_DIR = "generated/keel/orm"

        val SUPPORTED_DIALECTS = setOf("sqlite", "mariadb", "mysql")
    }
}
