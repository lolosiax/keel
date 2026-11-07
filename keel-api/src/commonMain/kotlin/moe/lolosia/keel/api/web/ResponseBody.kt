package moe.lolosia.keel.api.web

import kotlinx.coroutines.flow.Flow

/**
 * 响应体。
 *
 * - [Empty] / [Text] / [Bytes] 一次性返回；
 * - [Stream] 由 Host 边收边写，支持大文件和 SSE。
 */
sealed interface ResponseBody {

    data object Empty : ResponseBody

    data class Text(
        val text: String,
        val contentType: String = "text/plain; charset=utf-8",
    ) : ResponseBody

    data class Bytes(
        val bytes: ByteArray,
        val contentType: String = "application/octet-stream",
    ) : ResponseBody

    /**
     * @param flow 由插件 Worker 上收集；Host 写一块、回一个 ACK，形成背压
     */
    data class Stream(
        val flow: Flow<ByteArray>,
        val contentType: String,
    ) : ResponseBody
}
