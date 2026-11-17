package moe.lolosia.keel.plugins.hello

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.PublicApiKey

private const val HELLO_API_TYPE_ID = "moe.lolosia.keel.plugins.hello.HelloApi"

/**
 * 自检代理：只走 [PublicApiBridge]，验证「本插件登记的单例经 Host 路由回自己」。
 *
 * api 工件里的 `HelloApiProxy` 与 impl 同 FQN，不能直接依赖，所以自检保留这一小段手写转发。
 */
private class SelfCheckHelloApi(private val handle: Long) : HelloApi {

    override fun greet(name: String): String = ServiceWire.decode(
        String.serializer(),
        PublicApiBridge.call(
            handle,
            HELLO_API_TYPE_ID,
            "greet",
            ServiceWire.encode(String.serializer(), name),
        ),
    )

    override suspend fun echo(text: String): String = ServiceWire.decode(
        String.serializer(),
        PublicApiBridge.call(
            handle,
            HELLO_API_TYPE_ID,
            "echo",
            ServiceWire.encode(String.serializer(), text),
        ),
    )
}

/**
 * 走一次 Host 往返：`requireInstance` 取本插件的单例句柄 → 代理 → Host 转发回分发器。
 *
 * 正常时日志里出现 `服务自检：hello, service-check` 与 `服务自检（suspend）：echo: service-check`。
 */
internal actual fun checkHelloService(context: PluginContext) {
    val api = context.publicApi.requireInstance(PublicApiKey(HELLO_API_TYPE_ID)) {
        SelfCheckHelloApi(it)
    }
    Log.info("服务自检：${api.greet("service-check")}")
    runBlocking {
        Log.info("服务自检（suspend）：${api.echo("service-check")}")
    }

    // 等单例注册与权限目录发布后，再取一次聚合目录
    context.scheduler.schedule(250) {
        val nodes = context.host.permissionCatalog()
        Log.info("权限目录自检：${nodes.size} 个节点 ${nodes.joinToString { it.node }}")
        context.host.invalidateAuthorization()
    }
}
