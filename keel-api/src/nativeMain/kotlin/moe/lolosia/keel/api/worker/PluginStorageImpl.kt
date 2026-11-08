package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.storage.*

/**
 * 插件侧 [PluginStorage]：每次操作向 Host 发一条 `STORAGE` 反向请求。
 *
 * 路径校验、8 MB 上限与命名空间隔离都在 Host 侧完成，插件只拿相对路径。
 */
internal class PluginStorageImpl(private val link: HostLink) : PluginStorage {

    override suspend fun read(path: String): ByteArray? = request(StorageOp.READ, path, null)

    override suspend fun write(path: String, bytes: ByteArray) {
        request(StorageOp.WRITE, path, bytes)
    }

    override suspend fun delete(path: String) {
        request(StorageOp.DELETE, path, null)
    }

    private fun request(op: StorageOp, path: String, data: ByteArray?): ByteArray? {
        val payload = ServiceWire.encode(
            StorageRequest.serializer(),
            StorageRequest(op = op, path = path, data = data),
        )
        val reply = ServiceWire.decode(
            StorageReply.serializer(),
            link.request(HostSubjects.STORAGE, payload),
        )
        return reply.data
    }
}
