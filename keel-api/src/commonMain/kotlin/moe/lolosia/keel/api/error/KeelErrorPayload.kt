package moe.lolosia.keel.api.error

import kotlinx.serialization.Serializable

/**
 * 一帧远端栈：模块名 + 模块内偏移是主要信息，地址仅作参考，
 * 符号保留原始形式（`kfun:` / `_Z`），由接收方格式化。
 */
@Serializable
data class KeelErrorFrame(
    val module: String = "",
    val rva: Long = 0,
    val address: Long = 0,
    val symbol: String = "",
)

/** 错误链的一层；帧从这里开始按 Java 风格渲染。 */
@Serializable
data class KeelErrorEntry(
    val type: String,
    val message: String = "",
    val frames: List<KeelErrorFrame> = emptyList(),
    /** 构造点 `类.函数:行`；未插桩或记录过期时为 null。 */
    val createdAt: String? = null,
    /** 抛出点（可能多次重抛）。 */
    val throwSites: List<String> = emptyList(),
)

/** 跨运行时传递的错误信封：外层在前，`Caused by:` 依次向后。 */
@Serializable
data class KeelErrorPayload(
    val plugin: String = "",
    val chain: List<KeelErrorEntry> = emptyList(),
    val truncated: Boolean = false,
)
