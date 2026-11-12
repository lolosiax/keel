package moe.lolosia.keel.host.plugin

/** 插件数字 id 与日志显示名（`plugin.json` 的 `logTag`，缺失时为 `name` / `id`）的映射。 */
internal object PluginNames {

    private val names = mutableMapOf<ULong, String>()

    fun register(id: ULong, name: String) {
        names[id] = name
    }

    fun of(id: ULong): String = names[id] ?: id.toString()
}
