package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDispatcher
import moe.lolosia.keel.api.share.SharedRef
import moe.lolosia.keel.plugins.animal.CALLABLE_TYPE_ID

/**
 * `SpawnAnimal` 在实例方侧的分发器：`invoke` 的返回值换成 Dog 的句柄。
 */
class SpawnAnimalDispatcher(
    private val impl: SpawnAnimal,
    private val dog: SharedRef,
) : PublicApiDispatcher {

    override val typeId: String = CALLABLE_TYPE_ID

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray = when (method) {
        "invoke" -> {
            impl.invoke()
            ServiceWire.encode(SharedRef.serializer(), dog)
        }

        else -> throw IllegalArgumentException("SpawnAnimal 没有方法 $method")
    }
}
