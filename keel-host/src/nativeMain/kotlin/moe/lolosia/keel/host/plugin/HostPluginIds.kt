package moe.lolosia.keel.host.plugin

/**
 * 数字 plugin id → manifest id 的映射。
 *
 * 数字 id 每次启动重新分配，不能用来命名磁盘上的命名空间；配置与存储都按 manifest id
 * 落盘。映射在 `onLoad` 之前登记，插件停用 / 启动失败时撤销。
 */
internal object HostPluginIds {

    private val names = mutableMapOf<ULong, String>()

    fun register(pluginId: ULong, name: String) {
        names[pluginId] = name
    }

    fun nameOf(pluginId: ULong): String? = names[pluginId]

    fun release(pluginId: ULong) {
        names.remove(pluginId)
    }
}
