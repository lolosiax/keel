package moe.lolosia.keel.orm

import kotlin.reflect.KClass

/**
 * 当前插件运行时的实体注册表。
 *
 * 生成的 `OrmMetaBootstrap.install()` 在插件 Worker 启动时登记全部 `@Table`；
 * 同一个运行时里重复登记是幂等的。Kotlin/Native 没有反射，所以这里按 `KClass` 查表。
 */
object EntityMetas {

    private val all = mutableMapOf<KClass<*>, EntityMeta<*>>()

    fun register(meta: EntityMeta<*>) {
        all[meta.type] = meta
    }

    fun types(): Set<KClass<*>> = all.keys

    fun <T : Any> of(type: KClass<T>): EntityMeta<T> {
        val meta = all[type] ?: error(
            "实体 ${type.simpleName} 未注册：请确认它标了 @Table，" +
                "并已应用 moe.lolosia.keel.database 插件（生成 OrmMetaBootstrap）"
        )
        @Suppress("UNCHECKED_CAST")
        return meta as EntityMeta<T>
    }
}
