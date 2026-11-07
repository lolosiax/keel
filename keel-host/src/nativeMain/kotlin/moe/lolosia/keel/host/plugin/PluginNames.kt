package moe.lolosia.keel.host.plugin

/** 插件数字 id 与 `.keel` manifest id 的映射，供 Host 日志标注来源。 */
internal object PluginNames {

    private val names = mutableMapOf<ULong, String>()

    fun register(id: ULong, name: String) {
        names[id] = name
    }

    fun of(id: ULong): String = names[id] ?: id.toString()
}
