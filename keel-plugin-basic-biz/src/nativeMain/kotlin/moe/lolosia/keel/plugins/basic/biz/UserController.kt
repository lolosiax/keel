package moe.lolosia.keel.plugins.basic.biz

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.db.AccountRepository
import moe.lolosia.keel.plugins.basic.biz.db.UserRepository
import moe.lolosia.keel.plugins.basic.biz.db.displayTime
import moe.lolosia.keel.plugins.basic.biz.db.toRow
import moe.lolosia.keel.plugins.rbac.api.ROLE_ID_USER
import moe.lolosia.keel.plugins.rbac.api.RbacServices

/**
 * 用户接口（路径与请求 / 响应形状照 backend 的 `UserController`，供前端 `core/api/user.ts` 直接复用）。
 *
 * 全部要求登录（`/api/user` 前缀不在白名单里），管理动作的细粒度权限等 rbac 插件落地后
 * 再加 `@Permission`；当前只区分「登录 / 未登录」。
 */
@Controller("/api")
object UserController {

    // ===== 入参 =====

    @Serializable
    data class UserListReq(
        val pageNo: Int? = null,
        val pageSize: Int? = null,
        val keys: String? = null,
    )

    @Serializable
    data class SearchReq(val keys: String? = null)

    @Serializable
    data class IdListReq(val idList: List<String?> = emptyList())

    /** 前端两种形状都发过：数组 `["id"]` 或逗号串 `"a,b"`。 */
    @Serializable
    data class IdsReq(
        @Serializable(with = IdsSerializer::class)
        val ids: List<String> = emptyList(),
    )

    @Serializable
    data class UpdatePasswordReq(
        val id: String? = null,
        val origin: String,
        val target: String,
    )

    // ===== 出参 =====

    @Serializable
    class EmptyResult

    @Serializable
    data class IdRow(val id: String)

    @Serializable
    data class UserInfoRow(
        val id: String,
        val userName: String,
        val realName: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val avatar: String? = null,
        val isUse: Int? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
        val roleId: Long = ROLE_ID_USER,
    )

    /** `rules` 只有 `/user/myRole` 会填；其余接口给 null。 */
    @Serializable
    data class UserRoleRow(
        val id: Long = 0L,
        val userId: String,
        val userName: String? = null,
        val realName: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val avatar: String? = null,
        val roleId: Long = ROLE_ID_USER,
        val roleName: String? = null,
        val type: String? = null,
        val roleType: String? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
        val rules: List<String>? = null,
    )

    // ===== 路由 =====

    @POST("/user/list")
    suspend fun list(@Body req: UserListReq): UserPage =
        UserRepository.page(req.pageNo ?: 1, req.pageSize ?: 10, req.keys)

    @POST("/user/searching")
    suspend fun searching(@Body req: SearchReq): List<UserRow> =
        UserRepository.search(req.keys, 10).map { it.toRow() }

    @POST("/user/get")
    suspend fun get(@Body req: IdListReq): List<UserRow> =
        UserRepository.byIds(req.idList.filterNotNull()).map { it.toRow() }

    @POST("/user/create")
    suspend fun create(ctx: Context, @Body req: UserCreateReq): IdRow =
        IdRow(UserRepository.create(req, ctx.requireUserId()).id)

    @POST("/user/edit")
    suspend fun edit(ctx: Context, @Body req: UserUpdateReq): EmptyResult {
        UserRepository.update(req, ctx.requireUserId())
        return EmptyResult()
    }

    @POST("/user/delete")
    suspend fun delete(ctx: Context, @Body req: IdsReq): EmptyResult {
        if (req.ids.isNotEmpty()) {
            val operator = ctx.requireUserId()
            UserRepository.softDelete(req.ids, operator)
            req.ids.forEach { AccountRepository.revokeByUser(it) }
        }
        return EmptyResult()
    }

    @POST("/user/updatePassword")
    suspend fun updatePassword(ctx: Context, @Body req: UpdatePasswordReq): EmptyResult {
        UserRepository.changePassword(req.id ?: ctx.requireUserId(), req.origin, req.target)
        return EmptyResult()
    }

    @POST("/user/myInfo")
    suspend fun myInfo(ctx: Context): UserInfoRow {
        val userId = ctx.requireUserId()
        val user = UserRepository.findById(userId) ?: throw ApiException(401, "用户不存在")
        val role = UserRepository.roleOf(userId)
        return UserInfoRow(
            id = user.id,
            userName = user.userName,
            realName = user.realName,
            phone = user.phone,
            email = user.email,
            avatar = user.avatar,
            isUse = user.isUse,
            createdAt = displayTime(user.createdAt),
            updatedAt = displayTime(user.updatedAt),
            roleId = role?.roleId ?: ROLE_ID_USER,
        )
    }

    /** 当前用户的组 + 生效规则；rbac 未安装时规则为空（前端守卫按空规则处理）。 */
    @POST("/user/myRole")
    suspend fun myRole(ctx: Context): UserRoleRow {
        val userId = ctx.requireUserId()
        val user = UserRepository.findById(userId) ?: throw ApiException(401, "用户不存在")
        val role = UserRepository.roleOf(userId)
        val rules = ctx.modules.get(RbacServices.reader)
            ?.let { runCatching { it.effectiveRules(userId) }.getOrNull() }
            .orEmpty()
        return UserRoleRow(
            id = role?.bindingId ?: 0L,
            userId = userId,
            userName = user.userName,
            realName = user.realName,
            phone = user.phone,
            email = user.email,
            avatar = user.avatar,
            roleId = role?.roleId ?: ROLE_ID_USER,
            roleName = role?.roleName,
            type = role?.type,
            roleType = role?.type,
            createdAt = displayTime(user.createdAt),
            updatedAt = displayTime(user.updatedAt),
            rules = rules,
        )
    }

    /** 数组 / 逗号串 / 单条字符串都读成 id 列表。 */
    object IdsSerializer : KSerializer<List<String>> {

        private val delegate: KSerializer<List<String>> = ListSerializer(String.serializer())

        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: List<String>) {
            encoder.encodeString(value.joinToString(","))
        }

        override fun deserialize(decoder: Decoder): List<String> {
            val json = decoder as? JsonDecoder ?: return split(decoder.decodeString())
            val element = json.decodeJsonElement()
            return if (element is JsonArray) {
                element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(String::trim)
                    .filter(String::isNotEmpty)
            } else {
                split((element as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
        }

        private fun split(raw: String): List<String> =
            raw.split(',').map(String::trim).filter(String::isNotEmpty)
    }

    private fun Context.requireUserId(): String =
        userId ?: throw ApiException(401, "身份认证失败，请重新登录")
}
