package moe.lolosia.keel.host

import kotlinx.coroutines.channels.Channel

/**
 * Host 本地订阅（控制台 / 测试）的唤醒登记。
 *
 * commonMain 只需要「登记 / 注销」两个动作；跨运行时唤醒的实现在 native 侧，
 * 由 [LogHub] 统一维护。
 */
internal expect fun registerLocalLogWake(channel: Channel<Unit>): Long

internal expect fun unregisterLocalLogWake(subscriptionId: Long)
