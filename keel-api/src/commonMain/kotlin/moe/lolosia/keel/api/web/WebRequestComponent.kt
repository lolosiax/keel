package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.annotation.PublicApi

/**
 * HTTP 请求组件：只有 Web 场景的 Context 才挂载。
 *
 * 请求信息是一次性快照；权限判定经 Host 在网络层完成，快照里的
 * [UserSnapshot.rules] 已展开，所以插件侧判定不产生跨运行时调用。
 */
@PublicApi
interface WebRequestComponent : Component {

    @PublicApi
    suspend fun request(): HttpRequest

    @PublicApi
    suspend fun pathParameters(): Map<String, String>

    /** 当前登录用户；未登录返回 null。 */
    @PublicApi
    suspend fun user(): UserSnapshot?

    companion object {
        /** 组件标识；也是 `Context.component(id)` 的查询键。 */
        const val TYPE_ID: String = "keel.web.request"
    }
}
