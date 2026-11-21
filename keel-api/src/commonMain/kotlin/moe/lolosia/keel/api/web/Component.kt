package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 挂在 [Context] 上的公共组件基接口。
 *
 * 组件是 Context 的一等公民：Web 请求、用户、Session 等能力都作为组件挂载，
 * 而不是写进 [Context] 核心。公共组件标 [PublicApi]，可以跨插件；
 * 只在本插件内使用的组件不实现本接口，直接放在 [LocalContext.local]。
 */
@PublicApi
interface Component {

    /**
     * 当前挂载的 Context；由 [Context.addComponent] / [Context.removeComponent] 维护。
     *
     * 这是本地生命周期状态，不属于跨插件契约面，实现方不要自己改；
     * 实现类声明 `override var context: Context? = null` 即可。
     * 非 null 表示组件仍挂载，此时 [destroy] 应当直接返回。
     */
    var context: Context?

    /**
     * 组件标识；同一个 Context 内唯一。
     *
     * 组合使用时不要拿它当类型 id：类型由声明方的 typed 方法暴露
     * （如 `WebContext.webRequest()`），不要用 `component(id) as Xxx` 跨插件强转。
     */
    @PublicApi
    suspend fun id(): String

    /**
     * 释放组件占用的资源。
     *
     * 默认实现只在组件尚未挂载（[context] 为 null）时生效；挂载期间调用无效果。
     * [Context.destroy] 会先解除挂载、再按逆序调用本方法，所以级联释放能正常执行。
     *
     * 有资源的组件覆盖本方法时，要保留 `if (context != null) return` 这个判断。
     */
    @PublicApi
    suspend fun destroy() {
        if (context != null) return
    }
}
