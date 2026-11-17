// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

@Serializable
internal class UserDirectoryCallPageArgs(
    val pageNo: Int,
    val pageSize: Int,
    val keyword: String?
)


@Serializable
internal class UserDirectoryCallSearchArgs(
    val keyword: String?,
    val limit: Int
)


/** `moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory` 在消费方运行时里的代理。 */
class UserDirectoryProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory {

    override suspend fun byId(id: String): moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow? {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow?>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory", "byId", ServiceWire.encode(serializer<String>(), id)))
    }

    override suspend fun byIds(ids: kotlin.collections.List<String>): kotlin.collections.List<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow> {
        return ServiceWire.decode(serializer<kotlin.collections.List<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory", "byIds", ServiceWire.encode(serializer<kotlin.collections.List<String>>(), ids)))
    }

    override suspend fun page(pageNo: Int, pageSize: Int, keyword: String?): moe.lolosia.keel.plugins.basic.biz.api.dto.UserPage {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.UserPage>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory", "page", ServiceWire.encode(serializer<UserDirectoryCallPageArgs>(), UserDirectoryCallPageArgs(pageNo = pageNo, pageSize = pageSize, keyword = keyword))))
    }

    override suspend fun search(keyword: String?, limit: Int): kotlin.collections.List<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow> {
        return ServiceWire.decode(serializer<kotlin.collections.List<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory", "search", ServiceWire.encode(serializer<UserDirectoryCallSearchArgs>(), UserDirectoryCallSearchArgs(keyword = keyword, limit = limit))))
    }
}
