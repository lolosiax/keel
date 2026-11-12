package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/** 退订通知；订阅 id 不存在时 Host 直接忽略。 */
@Serializable
data class LogUnsubscribeRequest(val subscriptionId: Long)
