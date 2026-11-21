package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.annotation.PublicApiProvider
import moe.lolosia.keel.api.web.*

/**
 * [WebRequestComponent] 的本地实现：只持有一份请求快照，没有需要释放的资源。
 */
@PublicApiProvider(WebRequestComponent::class, Component::class)
internal class WebRequestComponentImpl(
    private val requestData: HttpRequest,
    private val pathData: Map<String, String>,
    private val userData: UserSnapshot?,
) : WebRequestComponent {

    override var context: Context? = null

    override suspend fun id(): String = WebRequestComponent.TYPE_ID

    override suspend fun request(): HttpRequest = requestData

    override suspend fun pathParameters(): Map<String, String> = pathData

    override suspend fun user(): UserSnapshot? = userData
}
