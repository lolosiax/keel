package moe.lolosia.keel.gradle.classpath

/**
 * 普通 Kotlin/Native 产物的 `classpath.zip` 嵌入扩展。
 *
 * 与 `.keel` 插件打包是两条独立链路：这里把资源合并后嵌入可执行文件，
 * 运行期用 `installEmbeddedClasspath()` 读取。
 */
open class KeelClasspathExtension {

    var enabled: Boolean = true

    /** 参与嵌入的目标；为空则取全部 native 目标。 */
    val targets: MutableList<String> = mutableListOf()

    /** 是否压缩 zip 条目；读取端两种都支持。 */
    var compressed: Boolean = false

    /** 额外资源目录。 */
    val resources: MutableList<Any> = mutableListOf()

    /** Windows 宿主编译 Linux 目标时借道 WSL。 */
    var wslDistro: String? = null
    var wslUser: String? = null

    fun target(name: String) {
        targets += name
    }

    fun targets(vararg names: String) {
        targets += names
    }

    fun resources(vararg paths: Any) {
        resources.addAll(paths)
    }
}
