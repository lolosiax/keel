package moe.lolosia.keel.api.debug

/**
 * 调试通道的底层 socket 差异。
 *
 * Windows 与 Linux 的「句柄宽度 / 关闭 / 非阻塞 / 初始化」不同，其余流程共用 [Debug] 里的
 * 帧协议；所有调用都是非阻塞或短暂的，不引入额外线程。
 *
 * 句柄统一用 `Long`：Windows 的 `SOCKET` 是 64 位，Linux 是 `Int`，在各自的 actual 里转换。
 * 方向是 **IDE 监听、各 image 主动连接**：一个 image（Host / 每个插件 dylib）一条通道。
 */

/** Winsock 初始化；Linux 上空操作。 */
internal expect fun debugSocketInit(): Boolean

/** 连接 `127.0.0.1:port`；失败返回 -1。 */
internal expect fun debugSocketConnect(port: Int): Long

/** 切成非阻塞模式；失败返回 false。 */
internal expect fun debugSocketSetNonBlocking(fd: Long): Boolean

/**
 * 非阻塞读。
 *
 * @return 读到的字节数；-1 表示暂时没有数据或出错
 */
internal expect fun debugSocketRead(fd: Long, buffer: ByteArray, offset: Int, length: Int): Int

/** 写完整个缓冲；对端已关闭或出错时返回 false。 */
internal expect fun debugSocketWrite(fd: Long, buffer: ByteArray, offset: Int, length: Int): Boolean

internal expect fun debugSocketClose(fd: Long)

internal expect fun debugSleep(millis: Int)
