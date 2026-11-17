// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDispatcher

/** `DogDispatcher` 覆盖的方法线名；登记句柄时上报。 */
val DogOverrides: Set<String> = setOf("bark")

/** `Dog` 的实例方分发器。 */
class DogDispatcher(private val impl: Dog) : PublicApiDispatcher {

    override val typeId: String = "moe.lolosia.keel.plugins.animal.Animal"

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

        else -> throw IllegalArgumentException("Dog 没有方法 $method")
    }
}
