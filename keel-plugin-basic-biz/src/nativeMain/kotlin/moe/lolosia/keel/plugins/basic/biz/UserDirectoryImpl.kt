package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.annotation.ModuleServiceProvider
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory
import moe.lolosia.keel.plugins.basic.biz.db.UserRepository
import moe.lolosia.keel.plugins.basic.biz.db.toRow

/** [UserDirectory] 的实现：只读热路径，全部走 `sys_user` / `view_user_role`。 */
@ModuleServiceProvider
class UserDirectoryImpl : UserDirectory {

    override suspend fun byId(id: String): UserRow? = UserRepository.findById(id)?.toRow()

    override suspend fun byIds(ids: List<String>): List<UserRow> =
        UserRepository.byIds(ids).map { it.toRow() }

    override suspend fun page(pageNo: Int, pageSize: Int, keyword: String?): UserPage =
        UserRepository.page(pageNo, pageSize, keyword)

    override suspend fun search(keyword: String?, limit: Int): List<UserRow> =
        UserRepository.search(keyword, limit).map { it.toRow() }
}
