package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.web.ApiException
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.crypto.BCrypt
import moe.lolosia.keel.plugins.basic.biz.db.AccountRepository
import moe.lolosia.keel.plugins.basic.biz.db.UserRepository
import moe.lolosia.keel.plugins.basic.biz.db.displayTime
import moe.lolosia.keel.plugins.basic.biz.db.toRow

/**
 * 登录 / 注册 / 会话的业务编排。
 *
 * 与 backend 的口径一致：token 是会话 id，不是 JWT；库里没有密码且本次也没传密码时
 * 视为首次登录直接放行。登录失败统一 400（前端只展示 `msg`）。
 */
internal object AuthService {

    fun login(account: String, password: String?, sessionTtlSeconds: Long): LoginResult {
        val user = UserRepository.findByAccount(account)
            ?: throw ApiException(400, "用户不存在！")

        val stored = user.password
        val firstLogin = stored.isNullOrEmpty() && password.isNullOrEmpty()
        if (!firstLogin && (stored.isNullOrEmpty() || password == null || !BCrypt.checkpw(password, stored))) {
            throw ApiException(400, "密码不正确")
        }

        val token = AccountRepository.create(user.id, sessionTtlSeconds)
        return LoginResult(
            id = user.id,
            userName = user.userName,
            realName = user.realName,
            phone = user.phone,
            email = user.email,
            avatar = user.avatar,
            isUse = user.isUse ?: 1,
            createdAt = displayTime(user.createdAt),
            updatedAt = displayTime(user.updatedAt),
            authorization = token,
            firstLogin = firstLogin,
        )
    }

    fun register(req: RegisterReq): UserRow {
        if (req.userName.isBlank()) throw ApiException(400, "用户名不能为空")
        if (req.password.isEmpty()) throw ApiException(400, "密码不能为空")
        if (req.phone.isNullOrBlank() && req.email.isNullOrBlank()) {
            throw ApiException(400, "手机号与邮箱至少填一个")
        }
        if (UserRepository.findByAccount(req.userName) != null) {
            throw ApiException(400, "用户名已被占用")
        }

        val created = UserRepository.create(
            UserCreateReq(
                userName = req.userName,
                password = req.password,
                phone = req.phone,
                email = req.email,
                roleId = UserRepository.defaultUserRoleId(),
            ),
            operatorId = null,
        )
        return created.toRow()
    }
}
