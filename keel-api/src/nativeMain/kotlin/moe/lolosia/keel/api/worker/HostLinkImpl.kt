package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.host.HostLink

/** [HostLink] 的 Worker 侧实现：通知直接投递，请求在本地重入事件泵等待。 */
internal object HostLinkImpl : HostLink {

    override fun post(subject: Long, payload: ByteArray) {
        postToHost(subject.toULong(), payload)
    }

    override fun request(subject: Long, payload: ByteArray): ByteArray =
        callHost(subject.toULong(), payload)
}
