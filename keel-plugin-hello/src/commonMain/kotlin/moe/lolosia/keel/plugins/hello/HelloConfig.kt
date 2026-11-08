package moe.lolosia.keel.plugins.hello

import kotlinx.serialization.Serializable

/**
 * hello 的插件私有配置（`config/plugins/hello.yaml`）。
 *
 * @property greeting 问候语
 * @property repeat 重复次数；文件里没有这个键时写入会自动补上，用来验证「新增键」
 */
@Serializable
data class HelloConfig(
    val greeting: String = "hello from keel",
    val repeat: Int = 1,
)
