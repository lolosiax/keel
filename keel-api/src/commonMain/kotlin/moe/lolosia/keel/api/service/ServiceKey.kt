package moe.lolosia.keel.api.service

/**
 * 服务查找键。
 *
 * 跨模块只认 `id`；`T` 是本地类型承诺，不参与运行期校验（Native 上没有反射）。
 * 跨模块取到的 `T` 是**本地代理**，方法调用经事件泵转发。
 */
class ServiceKey<T : Any>(val id: String) {

    /** 只按 id 比较：契约库与生成代码各持一个实例时仍然能查到同一个服务。 */
    override fun equals(other: Any?): Boolean = other is ServiceKey<*> && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ServiceKey($id)"
}
