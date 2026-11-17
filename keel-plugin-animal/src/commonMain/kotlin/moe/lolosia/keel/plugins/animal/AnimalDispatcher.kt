package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.annotation.PublicApiHandler
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.PublicApiDispatcher

/**
 * `Animal` 在声明方侧的分发器。
 *
 * 每个句柄一个影子；覆盖方法按实例方上报的 override 集合决定「本地跑基类体」还是
 * 「转回实例方」；`base.` 前缀永远只跑基类体，防止影子与镜像互相转发。
 */
@PublicApiHandler(Animal::class)
class AnimalDispatcher : PublicApiDispatcher {

    override val typeId: String = ANIMAL_TYPE_ID

    private val shadows = mutableMapOf<Long, AnimalShadow>()

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray {
        val shadow = shadows.getOrPut(handle) { AnimalShadow() }
        return when (method) {
            "bark" -> {
                if ("bark" in overrides) {
                    forward(handle, "bark")
                } else {
                    shadow.bark()
                }
                ByteArray(0)
            }

            "base.bark" -> {
                shadow.baseBark()
                ByteArray(0)
            }

            "describe" -> {
                val text = if ("describe" in overrides) {
                    ServiceWire.decode(String.serializer(), forward(handle, "describe"))
                } else {
                    shadow.describe()
                }
                ServiceWire.encode(String.serializer(), text)
            }

            "base.describe" ->
                ServiceWire.encode(String.serializer(), shadow.baseDescribe())

            else -> throw IllegalArgumentException("Animal 没有方法 $method")
        }
    }

    private fun forward(handle: Long, method: String): ByteArray =
        PublicApiBridge.call(handle, ANIMAL_TYPE_ID, method, ByteArray(0), owner = true)
}
