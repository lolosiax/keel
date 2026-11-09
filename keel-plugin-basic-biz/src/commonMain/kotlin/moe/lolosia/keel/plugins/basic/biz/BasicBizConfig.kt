package moe.lolosia.keel.plugins.basic.biz

import kotlinx.serialization.Serializable

/**
 * basic-biz 的私有配置（`config/plugins/basic-biz.yaml`）。
 *
 * 运行时设置也落在同一份文件里：设置面板改完立即写回，重启后仍在。
 *
 * @property allowHiddenFiles 是否允许客户端访问隐藏项（`.` 开头，以及 `lost+found`）
 * @property sessionTtlSeconds 会话有效期（秒）；过期后 `Authenticator` 返回 null
 */
@Serializable
data class BasicBizConfig(
    val allowHiddenFiles: Boolean = false,
    val sessionTtlSeconds: Long = 7L * 24 * 3600,
)
