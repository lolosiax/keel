package moe.lolosia.keel.gradle.publish

/**
 * `moe.lolosia.keel.publish` 的配置项。
 *
 * @property url 目标 Maven 仓库地址
 * @property username 仓库账号（无鉴权时留空）
 * @property password 仓库口令（无鉴权时留空）
 */
open class KeelPublishExtension {

    var url: String = ""

    var username: String = ""

    var password: String = ""
}
