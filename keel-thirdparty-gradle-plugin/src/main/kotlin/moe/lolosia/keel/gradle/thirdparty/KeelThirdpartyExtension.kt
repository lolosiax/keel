package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.Project
import java.io.File

/**
 * `moe.lolosia.keel.thirdparty` 的配置项。
 *
 * ```kotlin
 * import moe.lolosia.keel.gradle.common.*
 *
 * keel {
 *     thirdparty {
 *         library("sqlite")
 *         library("zlib") { linkerOpts("-lpthread") }
 *         library("libpng", dir = file("local/libpng"))
 *     }
 * }
 * ```
 *
 * 只 apply 本插件、不 apply 任何 keel 运行时插件时，它就是一个普通的
 * Kotlin/Native 第三方库接线插件。
 */
open class KeelThirdpartyExtension(private val project: Project) {

    var enabled: Boolean = true

    /** 原生库工件版本；为空时取工程版本。 */
    var version: String? = null

    /** 原生库工件的 group。 */
    var group: String = "moe.lolosia.keel.thirdparty"

    /** manifest 路径（相对工程根）；CI 门禁与声明生成都读它。 */
    var manifest: String = "thirdparty/manifest.json"

    /** 发布打包时的本地产物根目录：`<publishFrom>/<lib>/<target>/{include,lib}`。 */
    var publishFrom: String = "thirdparty/dist"

    /** 生成 `THIRD-PARTY-NOTICES.md` / `SBOM.json` 的目录。 */
    var noticesDirectory: String = "build/thirdparty"

    /** 被消费的库。 */
    val libraries: NamedDomainObjectContainer<KeelThirdpartyLibrary> =
        project.container(
            KeelThirdpartyLibrary::class.java,
            NamedDomainObjectFactory<KeelThirdpartyLibrary> { name -> KeelThirdpartyLibrary(name) },
        )

    fun library(name: String): KeelThirdpartyLibrary = libraries.create(name)

    fun library(name: String, dir: File): KeelThirdpartyLibrary =
        libraries.create(name).apply { this.dir = dir.absolutePath }

    fun library(name: String, action: Action<KeelThirdpartyLibrary>): KeelThirdpartyLibrary =
        libraries.create(name).also { action.execute(it) }

    /** 解析后使用的工件版本。 */
    fun resolvedVersion(): String = version ?: project.version.toString()
}
