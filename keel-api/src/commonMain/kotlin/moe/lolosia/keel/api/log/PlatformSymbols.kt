package moe.lolosia.keel.api.log

/** 运行时地址的归属：模块名 + 模块内偏移。 */
data class FrameLocation(
    val module: String,
    val rva: Long,
)

/** 把 `_Z...` 还原成 C++ 声明；失败返回 null。 */
internal expect fun platformDemangle(raw: String): String?

/** 运行时地址 → 模块 + 偏移；失败返回 null。 */
internal expect fun platformLocate(address: Long): FrameLocation?
