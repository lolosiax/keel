package moe.lolosia.keel.api.log

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 一条日志的完整形态：渲染到控制台之前的结构化对象。
 *
 * [message] 是调用方原样传入的正文，Host 不解析、不加前缀、不转义；
 * 文本视图在读取时才由 [LogRecordFormat] 生成，缓冲区因此只存一份数据。
 *
 * @property id 缓冲区内单调 id，从 1 开始；淘汰后不复用
 * @property timeMillis 入队时的墙钟（epoch 毫秒），跨天也能排序
 * @property time Host 本地时间 `HH:mm:ss`，与控制台一致
 * @property module 已解析的模块名：显式 tag，或 Host / 插件的日志名
 * @property site 完整调用点；文本视图渲染时才按列宽压缩
 * @property source 自定义来源列（HTTP 的 `ip:port` 等），纯文本
 * @property error 结构化错误；没有异常时为 null
 * @property banner 横幅这类非标准行：文本视图原样输出，不加前缀
 * @property moduleAnsi 控制台强制模块色（SGR，如 `96`）；只在 Host 进程内使用，不序列化
 * @property sourceAnsi 已着色的来源列文本；只在 Host 进程内使用，不序列化
 */
@Serializable
data class LogRecord(
    val id: Long,
    val timeMillis: Long,
    val time: String,
    val level: LogLevel,
    val module: String,
    val site: LogSite? = null,
    val source: String? = null,
    val message: String,
    val error: LogError? = null,
    val banner: Boolean = false,
    @Transient val moduleAnsi: String? = null,
    @Transient val sourceAnsi: String? = null,
)
