package moe.lolosia.keel.api.storage

/**
 * 按插件命名空间隔离的最小存储。
 *
 * - 根目录是 Host 的 `<workDir>/plugins/<plugin-id>/`，[path] 是相对路径；
 * - 拒绝 `..`、绝对路径、空段与带盘符的段；
 * - 单对象上限 8 MB；v1 不提供目录列举；
 * - 插件停用 / 卸载不删数据，重新启用后仍可读。
 *
 * 只能在 Worker 线程调用：实现会经事件泵向 Host 发一次反向请求。
 */
interface PluginStorage {

    /** 读取整个对象；不存在返回 null。 */
    suspend fun read(path: String): ByteArray?

    /** 覆盖写入整个对象。 */
    suspend fun write(path: String, bytes: ByteArray)

    /** 删除对象；不存在视为成功。 */
    suspend fun delete(path: String)
}
