package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/**
 * 声明方插件里 `Animal` 在 `-api` 工件中的镜像。
 *
 * 镜像没有业务状态：基类体与裸字段都住在声明方的影子里。
 * 方法体是「基类桩」，只用于子类 `super.x()`；对远端实例的虚调用走 [AnimalProxy]。
 */
open class Animal {

    /** Host 分配的句柄；由登记方在首次跨界登记后写入。 */
    var sharedHandle: Long = 0L

    fun attachHandle(handle: Long) {
        sharedHandle = handle
    }

    open fun bark() {
        PublicApiBridge.call(sharedHandle, ANIMAL_TYPE_ID, "base.bark")
    }

    open fun describe(): String =
        ServiceWire.decode(
            String.serializer(),
            PublicApiBridge.call(sharedHandle, ANIMAL_TYPE_ID, "base.describe"),
        )
}
