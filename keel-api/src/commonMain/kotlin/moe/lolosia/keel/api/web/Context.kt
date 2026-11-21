package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 插件侧调用上下文：一个组件容器，**不假设一定有 Web 请求**。
 *
 * 核心只暴露组件查询；具体能力由子接口与扩展函数提供：
 * - Web 请求：[WebContext] / [WebRequestComponent]；
 * - 业务能力：各业务模块在 `Context` 上追加扩展（如数据库扩展）；
 * - 本插件私有的组件：放进 [LocalContext.local]，不实现 [Component]。
 *
 * 本地字段（`plugin` / `local` / `config` / `server`）在 [LocalContext]，
 * 不属于跨插件契约，见 `ContextExt.kt` 的扩展。
 */
@PublicApi(id = "keel.context")
interface Context {

    /** 当前上下文挂载的全部公共组件。 */
    @PublicApi
    suspend fun components(): List<Component>

    /** 按 [Component.id] 查询组件；不存在返回 null。 */
    @PublicApi
    suspend fun component(id: String): Component?

    /**
     * 挂载一个组件；任何插件在任意时刻都可以调用。
     *
     * 同 id 已存在时替换；替换掉的旧组件会被解绑，但不会被销毁。
     * 组件已在其它 Context 上挂载时抛 [IllegalArgumentException]。
     * 挂载期间组件的 [Component.destroy] 无效果，卸载也不代为销毁。
     */
    @PublicApi
    suspend fun addComponent(component: Component)

    /**
     * 从 Context 上移除组件，返回被移除的实例；不存在返回 null。
     *
     * 只解除挂载，不调用 [Component.destroy]；是否销毁由调用者决定。
     */
    @PublicApi
    suspend fun removeComponent(id: String): Component?

    /**
     * 销毁上下文：按挂载逆序调用每个组件的 [Component.destroy]，然后清空组件表。
     *
     * 持有方必须像 try-with-resources 一样保证它被执行（例如放在 `finally` 里）；
     * 重复调用是空操作。组件释放失败不会中断后续组件的销毁，第一个异常最后抛出。
     */
    @PublicApi
    suspend fun destroy()
}
