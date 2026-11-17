package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.annotation.PublicApiHandler
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.PublicApiDispatcher
import moe.lolosia.keel.api.share.SharedRef

/**
 * `SpawnAnimal` 在实例方侧的分发器。
 *
 * `initialization` 本地执行；`invoke` 的返回值换成 Dog 的句柄。
 * 标了 `@PublicApiHandler` 表示这份胶水由本插件手写，KSP 不再生成。
 */
@PublicApiHandler(SpawnAnimal::class)
class SpawnAnimalDispatcher(
    private val impl: SpawnAnimal,
    private val directory: PublicApiDirectory,
) : PublicApiDispatcher {

    override val typeId: String = SPAWN_ANIMAL_TYPE_ID

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray = when {
        method == "initialization" || method.startsWith("initialization/") -> {
            impl.initialization()
            ByteArray(0)
        }

        method == "invoke" -> {
            val animal = impl.invoke()
            ServiceWire.encode(
                SharedRef.serializer(),
                SharedRef(directory.handleOf(animal)),
            )
        }

        else -> throw IllegalArgumentException("SpawnAnimal 没有方法 $method")
    }
}
