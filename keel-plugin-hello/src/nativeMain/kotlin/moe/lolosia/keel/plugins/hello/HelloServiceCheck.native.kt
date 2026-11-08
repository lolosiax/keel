package moe.lolosia.keel.plugins.hello

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.service.ServiceKey
import moe.lolosia.keel.api.service.ServiceWire

/**
 * 走一次 Host 往返：编码参数 → Host 按服务 id 转发 → 本插件的生成分发器执行 → 回包。
 *
 * KSP 产物只进入平台源集（`mingwX64Main`），共享的 `nativeMain` 看不到生成的客户端，
 * 所以这里按 CBOR 结构调用同一份契约；字段名与生成的分发器包装一致。
 * 正常时日志里出现 `服务自检：hello, service-check`。
 */
@Serializable
private data class GreetArgs(val name: String)

internal actual fun checkHelloService(context: PluginContext) {
    val caller = context.modules.caller(ServiceKey("hello"))
    val reply = caller.callBlocking(
        method = "greet",
        payload = ServiceWire.encode(serializer<GreetArgs>(), GreetArgs("service-check")),
    )
    Log.info("服务自检：${ServiceWire.decode(serializer<String>(), reply)}")

    // 等服务把 /api/hello/greeting 的权限节点发布给 Host 后，再取一次聚合目录
    context.scheduler.schedule(250) {
        val nodes = context.host.permissionCatalog()
        Log.info("权限目录自检：${nodes.size} 个节点 ${nodes.joinToString { it.node }}")
        context.host.invalidateAuthorization()
    }
}
