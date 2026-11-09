package moe.lolosia.keel.plugins.basic.biz

import kotlin.uuid.Uuid

/** 用户 / 会话主键；与 backend 一样用 UUID 字符串。 */
internal fun newIdentifier(): String = Uuid.random().toString()
