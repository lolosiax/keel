package moe.lolosia.keel.database

import kotlinx.cinterop.*
import moe.lolosia.keel.api.worker.PluginCapabilities
import moe.lolosia.keel.database.c.*

/** 最近一次构造的数据库；能力句柄变化（插件重新启用）时重建。 */
private var cachedDatabase: KeelDatabase? = null
private var cachedHandle: COpaquePointer? = null

/**
 * 取当前插件可用的数据库。
 *
 * 没有安装 database 能力包时返回 null；同一个插件多次调用返回同一实例。
 */
fun currentDatabase(): KeelDatabase? {
    val api = PluginCapabilities.databaseApi ?: return null
    val handle = PluginCapabilities.databaseHandle ?: return null
    if (cachedHandle == handle) return cachedDatabase

    val database = KeelDatabase(api.reinterpret<KeelDbApi>(), handle.reinterpret<KeelDbHandle>())
    cachedDatabase = database
    cachedHandle = handle
    return database
}
