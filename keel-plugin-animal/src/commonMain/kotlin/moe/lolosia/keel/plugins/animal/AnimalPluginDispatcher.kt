package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.annotation.PublicApiHandler
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDispatcher
import moe.lolosia.keel.api.share.SharedRef

/**
 * 声明方插件单例的所有者侧分发器。
 *
 * 只导出 [AnimalPlugin.register]；参数里的共享对象按句柄解码成本地代理。
 */
@PublicApiHandler(AnimalPlugin::class)
class AnimalPluginDispatcher(private val impl: AnimalPlugin) : PublicApiDispatcher {

    override val typeId: String = ANIMAL_PLUGIN_TYPE_ID

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray = when (method) {
        "register" -> {
            val ref = ServiceWire.decode(SharedRef.serializer(), payload)
            impl.register(CallableProxy(ref.handle))
            ByteArray(0)
        }

        else -> throw IllegalArgumentException("AnimalPlugin 没有方法 $method")
    }
}
