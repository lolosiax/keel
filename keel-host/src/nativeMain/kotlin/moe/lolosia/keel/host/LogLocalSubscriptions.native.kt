package moe.lolosia.keel.host

import kotlinx.coroutines.channels.Channel

internal actual fun registerLocalLogWake(channel: Channel<Unit>): Long =
    LogHub.registerLocal(channel)

internal actual fun unregisterLocalLogWake(subscriptionId: Long) {
    LogHub.unregisterLocal(subscriptionId)
}
