package moe.lolosia.keel.host.shared

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.share.PublicApiType
import moe.lolosia.keel.api.share.SharedCall
import moe.lolosia.keel.api.share.SharedRegistration

/**
 * Host 侧的共享对象表：句柄 → 所有者 / 声明方 / 导出类型。
 *
 * - 句柄由 Host 分配，随登记返回给插件；
 * - [types] 记录每个类型由谁声明、是否带基类体，决定调用先路由到声明方还是直接给所有者；
 * - [singletons] 是 `getInstance` 的惰性入口：调用里只给 typeId 时按这里查句柄。
 *
 * 只在泵线程访问，因此不加锁。
 */
internal object HostShared {

    class Entry(
        val owner: ULong,
        val declaring: ULong,
        val types: Set<String>,
        val overrides: Set<String>,
        val singleton: Boolean,
    )

    private class TypeEntry(val declaring: ULong, val hasBase: Boolean)

    private val entries = mutableMapOf<Long, Entry>()
    private val types = mutableMapOf<String, TypeEntry>()
    private val singletons = mutableMapOf<String, Long>()
    private var nextHandle = 1L

    /** 声明方启用时上报自己导出的类型。 */
    fun declare(pluginId: ULong, declared: List<PublicApiType>) {
        declared.forEach { types[it.typeId] = TypeEntry(pluginId, it.hasBase) }
        Log.info("declared ${declared.size} public type(s) for plugin $pluginId", tag = "Shared")
    }

    /** 登记一个对象；返回 Host 分配的句柄。 */
    fun register(pluginId: ULong, registration: SharedRegistration): Long {
        val handle = nextHandle++
        val declaring = types[registration.typeId]?.declaring ?: pluginId
        val exported = (listOf(registration.typeId) + registration.interfaces).distinct().toSet()
        entries[handle] = Entry(
            owner = pluginId,
            declaring = declaring,
            types = exported,
            overrides = registration.overrides.toSet(),
            singleton = registration.singleton,
        )
        if (registration.singleton) singletons[registration.typeId] = handle
        Log.info(
            "registered ${registration.typeId} handle=$handle owner=$pluginId declaring=$declaring " +
                "singleton=${registration.singleton}",
            tag = "Shared",
        )
        return handle
    }

    fun entry(handle: Long): Entry? = entries[handle]

    /**
     * 一次调用应该发给谁。
     *
     * - [SharedCall.owner] 为 true：影子转发覆盖方法，直接给实例所有者；
     * - 带基类体的类型：先给声明方，由影子决定本地执行还是再转发；
     * - 纯接口：直接给所有者。
     */
    fun targetOf(entry: Entry, call: SharedCall): ULong {
        if (call.owner) return entry.owner
        val type = types[call.typeId]
        return if (type?.hasBase == true) type.declaring else entry.owner
    }

    fun singleton(typeId: String): Long? = singletons[typeId]

    /** 插件停用：清掉它拥有的对象、它声明的类型，以及以它为声明方的其它对象。 */
    fun removePlugin(pluginId: ULong) {
        val declared = types.filterValues { it.declaring == pluginId }.keys
        declared.forEach { types.remove(it) }

        val removed = entries.filterValues {
            it.owner == pluginId || it.declaring == pluginId || it.types.any(declared::contains)
        }.keys
        if (removed.isEmpty()) return

        removed.forEach { entries.remove(it) }
        singletons.entries.removeAll { it.value in removed }
        Log.info("released ${removed.size} shared object(s) for plugin $pluginId", tag = "Shared")
    }
}
