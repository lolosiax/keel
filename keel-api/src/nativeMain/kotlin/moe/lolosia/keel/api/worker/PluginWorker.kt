package moe.lolosia.keel.api.worker

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Plugin

/**
 * 插件 Worker 的通用运行时。
 *
 * KSP 生成的 `keel_plugin_worker_main` 只负责把插件类交给这里；实例的创建、事件分发与
 * 销毁都在本对象内完成。每个插件 dylib 只有一份状态，且只在事件泵线程上访问。
 */
object PluginWorker {

    /** 当前插件实例；只在 Worker 线程读写。 */
    internal var plugin: Plugin? = null

    /**
     * 构造插件实例并回填 C 侧的事件分发表。
     *
     * @param api  Host 传给 `keel_plugin_bootstrap` 的能力表，由 C 侧填好
     * @param out  分发表，C 侧在 START 之后用它转发所有事件
     * @param factory 创建插件实例（KSP 生成，指向 `@KeelPluginMain` 的类）
     * @return `KeelStatus`；C 侧据此回投 COMPLETE
     */
    fun enter(
        api: CPointer<KeelPluginApi>?,
        out: CPointer<KeelPluginHandlers>?,
        factory: () -> Plugin,
    ): UInt {
        if (api == null || out == null) return KEEL_ERR_INVALID
        if (api.pointed.abi_version != KEEL_ABI_VERSION) return KEEL_ERR_UNSUPPORTED

        plugin = factory()
        out.pointed.on_event = staticCFunction(::dispatch)
        return KEEL_OK
    }

    internal fun disable() {
        plugin?.onDisable()
        plugin = null
    }
}

/** 事件分发入口；`on_event` 不能捕获状态，所以放在顶层。 */
private fun dispatch(event: CPointer<KeelEvent>?) {
    val e = event?.pointed ?: return
    if (e.kind.toUInt() == KEEL_EVENT_SHUTDOWN) {
        PluginWorker.disable()
    }
}
