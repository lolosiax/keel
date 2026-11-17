package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/**
 * 声明方运行时里的 `Animal` 代理：把别的插件返回的句柄包成本地可调用的对象。
 */
class AnimalProxy(handle: Long) : Animal() {

    private val sharedHandle = handle

    override fun bark() {
        PublicApiBridge.call(sharedHandle, ANIMAL_TYPE_ID, "bark")
    }

    override fun describe(): String =
        ServiceWire.decode(
            String.serializer(),
            PublicApiBridge.call(sharedHandle, ANIMAL_TYPE_ID, "describe"),
        )
}
