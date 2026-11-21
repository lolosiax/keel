package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 带 Web 请求能力的 Context。
 *
 * 非 Web 场景（定时任务、控制台、插件内部调用）使用普通 [Context]，
 * 不实现本接口，也不伪造 HTTP 请求。
 */
@PublicApi
interface WebContext : Context {

    /** 当前请求的组件；实现方一定挂载了 [WebRequestComponent]。 */
    @PublicApi
    suspend fun webRequest(): WebRequestComponent?
}
