// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.annotation.PublicApi
import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.SharedProxy

/** `moe.lolosia.keel.plugins.animal.Animal` 在 api 工件里的镜像；对应 impl 的同名类型。 */
@PublicApi
open class Animal : SharedProxy {

    /** Host 分配的句柄；由登记方在首次跨界登记后写入。 */
    override var sharedHandle: Long = 0L

    open fun bark(): Unit {
        PublicApiBridge.call(sharedHandle, "moe.lolosia.keel.plugins.animal.Animal", "base.bark")
    }

    open fun describe(): String {
        return ServiceWire.decode(String.serializer(), PublicApiBridge.call(sharedHandle, "moe.lolosia.keel.plugins.animal.Animal", "base.describe"))
    }
}
