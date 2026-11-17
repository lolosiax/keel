package moe.lolosia.keel.api.share

/**
 * 共享类型的查找键。
 *
 * 跨插件只认 [typeId]；`T` 是本地类型承诺，运行期没有反射可以校验。
 */
class PublicApiKey<T : Any>(val typeId: String) {

    override fun equals(other: Any?): Boolean =
        other is PublicApiKey<*> && other.typeId == typeId

    override fun hashCode(): Int = typeId.hashCode()

    override fun toString(): String = "PublicApiKey($typeId)"
}
