package moe.lolosia.keel.api.config

import kotlinx.serialization.Serializable

/**
 * 配置读写的目标文件。
 *
 * - [MAIN]：Host 的 `application.yaml`；
 * - [PLUGIN]：当前插件自己的 `config/plugins/<id>.yaml`。
 *
 * 插件不能通过这里访问别的插件的配置。
 */
@Serializable
enum class ConfigScope {
    MAIN,
    PLUGIN,
}
