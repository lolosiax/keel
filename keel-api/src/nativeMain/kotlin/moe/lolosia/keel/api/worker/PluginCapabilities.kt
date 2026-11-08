package moe.lolosia.keel.api.worker

import kotlinx.cinterop.COpaquePointer

/**
 * Host 通过 `KeelHostApi` 下发给插件的跨运行时能力指针。
 *
 * 只允许对应的 `-api` 库读取（当前是 `keel-database-api`）；业务代码不要直接解引用，
 * 也不要把指针缓存到别的运行时。插件停用时一起清空。
 */
object PluginCapabilities {

    /** `KeelDbApi` 函数表；没有安装 database 能力包时为 null。 */
    var databaseApi: COpaquePointer? = null
        internal set

    /** database 能力实例（连接池）；与 [databaseApi] 同生命周期。 */
    var databaseHandle: COpaquePointer? = null
        internal set
}
