package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.FileZipClasspath
import kotlin.concurrent.Volatile

/**
 * Host 侧注册表实现。
 *
 * 当前实现把 `.keel` 整包读进内存（[FileZipClasspath] 的现状）；
 * 后续换成文件型随机读后，这里不需要改。
 *
 * 注册/卸载假定由 Host 的插件加载线程单线程调用；读取侧只读快照。
 */
class DefaultClasspathRegistry(
    override val host: Classpath,
) : ClasspathRegistry {

    private class State(
        val entry: PluginClasspathEntry,
        val generation: Long,
        val classpath: Classpath,
    )

    private var nextGeneration: Long = 1

    @Volatile
    private var states: Map<String, State> = emptyMap()

    override fun register(entry: PluginClasspathEntry): ClasspathRef {
        require(entry.pluginId.isNotBlank()) { "pluginId 不能为空" }
        require(entry.keelPath.isNotBlank()) { "keelPath 不能为空" }

        val classpath = FileZipClasspath.open(entry.keelPath, entry.resourcesPrefix)
        val generation = nextGeneration++
        val state = State(entry, generation, classpath)

        states = states + (entry.pluginId to state)
        return refOf(entry.pluginId, state)
    }

    override fun unregister(pluginId: String) {
        states = states - pluginId
    }

    override fun get(pluginId: String): ClasspathRef? {
        val state = states[pluginId] ?: return null
        return refOf(pluginId, state)
    }

    private fun refOf(pluginId: String, state: State): ClasspathRef =
        GuardedClasspath(
            pluginId = pluginId,
            generation = state.generation,
            isCurrent = {
                states[pluginId].let { current ->
                    current != null && current.generation == state.generation
                }
            },
            delegate = state.classpath,
        )
}
