package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.KeelServer
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.config.Config
import moe.lolosia.keel.api.share.SharedRef

/**
 * 同一运行时内的上下文扩展面。
 *
 * 这些成员只活在本插件的 Worker 里，不进入 api 工件、不跨插件；
 * 跨插件拿到的 Context 句柄只有 [Context] 的组件方法。
 * 控制器拿到的 `ctx` 静态类型是 [Context]，通过 `ContextExt.kt` 的扩展访问这些字段。
 */
interface LocalContext : Context {

    /** 插件运行时上下文。 */
    val plugin: PluginContext

    /**
     * 本次上下文共享的临时数据区；生命周期与 Context 相同。
     *
     * 不要跨请求 / 跨上下文持有。
     */
    val local: MutableMap<String, Any?>

    val server: KeelServer get() = plugin.server

    val config: Config get() = plugin.config

    /**
     * 把本 Context 登记为跨运行时共享对象，返回句柄；重复调用返回同一个句柄。
     *
     * 只在 Context 真的要传给其它插件时调用。`destroy()` 会负责释放句柄，
     * 释放后其它持有者的调用得到 “shared object closed”。
     */
    suspend fun shareHandle(): SharedRef
}
