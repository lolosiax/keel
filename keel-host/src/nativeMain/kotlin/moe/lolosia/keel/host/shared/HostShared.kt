package moe.lolosia.keel.host.shared

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.share.PublicApiType
import moe.lolosia.keel.api.share.SharedCall
import moe.lolosia.keel.api.share.SharedCreateReply
import moe.lolosia.keel.api.share.SharedRegistration

/**
 * Host 侧的共享对象表：句柄 → 所有者 / 声明方 / 导出类型 / 生命周期。
 *
 * - 句柄由 Host 分配，随登记或构造返回给插件；
 * - [types] 记录每个类型由谁声明、是否带基类体，决定调用先路由到声明方还是直接给所有者；
 * - [singletons] 是 `getInstance` 的惰性入口：调用里只给 typeId 时按这里查句柄；
 * - [Entry.state] 实现 `Creating → Open → Closed`：Creating 时只有创建者能调 `initialization`。
 *
 * 只在泵线程访问，因此不加锁。
 */
internal object HostShared {

    /** 共享对象的生命周期。 */
    enum class State { Creating, Open, Closed }

    class Entry(
        val owner: ULong,
        val declaring: ULong,
        val types: MutableSet<String>,
        val overrides: MutableSet<String>,
        val singleton: Boolean,
        val creator: ULong,
        var state: State,
    )

    private class TypeEntry(val declaring: ULong, val hasBase: Boolean)

    private val entries = mutableMapOf<Long, Entry>()
    private val types = mutableMapOf<String, TypeEntry>()
    private val singletons = mutableMapOf<String, Long>()
    private var nextHandle = 1L

    /**
     * 声明方启用时上报自己导出的类型。
     *
     * 同一 typeId 只认最先声明的插件：可替换能力（rbac 等）默认实现与第三方实现同时存在时，
     * 后加载的一方不会被静默串进来；它的实例仍可按句柄调用，只是不参与类型路由与单例查找。
     */
    fun declare(pluginId: ULong, declared: List<PublicApiType>) {
        declared.forEach { type ->
            val existing = types[type.typeId]
            if (existing != null && existing.declaring != pluginId) {
                Log.warn(
                    "类型 ${type.typeId} 已由插件 ${existing.declaring} 声明，忽略插件 $pluginId 的重复声明",
                    tag = "Shared",
                )
            } else {
                types[type.typeId] = TypeEntry(pluginId, type.hasBase)
            }
        }
        Log.info("declared ${declared.size} public type(s) for plugin $pluginId", tag = "Shared")
    }

    /** 类型由哪个插件声明；没有声明过的类型返回 null。 */
    fun declaringOf(typeId: String): ULong? = types[typeId]?.declaring

    /** 登记一个对象；返回 Host 分配的句柄。 */
    fun register(pluginId: ULong, registration: SharedRegistration): Long {
        val handle = nextHandle++
        val declaring = types[registration.typeId]?.declaring ?: pluginId
        val exported = (listOf(registration.typeId) + registration.interfaces).distinct().toSet()
        entries[handle] = Entry(
            owner = pluginId,
            declaring = declaring,
            types = exported.toMutableSet(),
            overrides = registration.overrides.toMutableSet(),
            singleton = registration.singleton,
            creator = pluginId,
            state = State.Open,
        )
        if (registration.singleton) {
            val existing = singletons[registration.typeId]
            val owner = existing?.let { entries[it]?.owner }
            if (existing != null && owner != pluginId) {
                Log.warn(
                    "单例 ${registration.typeId} 已被插件 $owner 占用，忽略插件 $pluginId 的注册",
                    tag = "Shared",
                )
            } else {
                singletons[registration.typeId] = handle
            }
        }
        Log.info(
            "registered ${registration.typeId} handle=$handle owner=$pluginId declaring=$declaring " +
                "singleton=${registration.singleton}",
            tag = "Shared",
        )
        return handle
    }

    /**
     * 构造请求先占一个句柄，等提供者回执再补全导出类型与覆盖集合。
     *
     * @param provider 负责实例化的插件（类型的声明方）
     * @param creator 发起构造的插件；只有它能完成 `initialization`
     */
    fun beginCreate(provider: ULong, typeId: String, creator: ULong): Long {
        val handle = nextHandle++
        entries[handle] = Entry(
            owner = provider,
            declaring = types[typeId]?.declaring ?: provider,
            types = mutableSetOf(typeId),
            overrides = mutableSetOf(),
            singleton = false,
            creator = creator,
            state = State.Creating,
        )
        Log.info(
            "creating $typeId handle=$handle creator=$creator provider=$provider",
            tag = "Shared",
        )
        return handle
    }

    /** 提供者回执：补全导出类型与覆盖集合；没有 `initialization` 的类型直接可用。 */
    fun completeCreate(handle: Long, reply: SharedCreateReply) {
        val entry = entries[handle] ?: return
        entry.types += reply.interfaces
        entry.overrides += reply.overrides
        if (!reply.initialization) entry.state = State.Open
        Log.info("created handle=$handle types=${entry.types} state=${entry.state}", tag = "Shared")
    }

    fun markOpen(handle: Long) {
        val entry = entries[handle] ?: return
        entry.state = State.Open
        Log.info("initialized handle=$handle", tag = "Shared")
    }

    fun markClosed(handle: Long) {
        val entry = entries[handle] ?: return
        entry.state = State.Closed
        Log.info("closed handle=$handle", tag = "Shared")
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
