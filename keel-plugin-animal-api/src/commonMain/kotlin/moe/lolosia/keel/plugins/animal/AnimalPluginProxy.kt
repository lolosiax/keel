package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedRef

/**
 * 声明方插件的本地代理。
 *
 * 参数里的共享对象按引用传：先换成句柄，再随调用一起投给声明方。
 */
class AnimalPluginProxy(
    private val handle: Long,
    private val directory: PublicApiDirectory,
) : AnimalPlugin {

    override suspend fun register(callable: Callable) {
        val ref = SharedRef(directory.handleOf(callable))
        PublicApiBridge.call(
            handle,
            ANIMAL_PLUGIN_TYPE_ID,
            "register",
            ServiceWire.encode(SharedRef.serializer(), ref),
        )
    }
}
