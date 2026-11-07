package moe.lolosia.keel.classpath

/**
 * 全局 classpath 持有者，供普通 Kotlin 程序（非 Keel 插件）使用。
 *
 * Keel 插件应使用 `PluginContext.classpath`；Host 侧的注册表在
 * `keel-classpath-full` 中，不依赖这个全局单例。
 */
object ClasspathHolder {

    private var current: Classpath? = null

    val installed: Boolean get() = current != null

    fun install(cp: Classpath) {
        current = cp
    }

    fun clear() {
        current = null
    }

    val instance: Classpath
        get() = current ?: error(
            "classpath 尚未初始化：请在启动前调用 ClasspathHolder.install(...)"
        )
}
