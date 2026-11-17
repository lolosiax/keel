package moe.lolosia.keel.plugins.dog

import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDispatcher
import moe.lolosia.keel.plugins.animal.ANIMAL_TYPE_ID

/**
 * `Dog` 在实例方侧的分发器。
 *
 * 只有声明方的影子把覆盖方法转回来时才会收到 `bark`；`super.bark()` 不再经过这里，
 * 而是走镜像桩回到声明方。
 */
class DogDispatcher(private val impl: Dog) : PublicApiDispatcher {

    override val typeId: String = ANIMAL_TYPE_ID

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray = when (method) {
        "bark" -> {
            impl.bark()
            ByteArray(0)
        }

        "describe" -> ServiceWire.encode(String.serializer(), impl.describe())

        else -> throw IllegalArgumentException("Dog 没有方法 $method")
    }
}
