package moe.lolosia.keel.api.log

/**
 * 结构化重建的远端异常。
 *
 * 这类异常的 [Throwable.toString] 已经把整条错误链、调用方帧和
 * `--- rethrown at host ---` 标记渲染完，本身只是宿主侧的载体；
 * 日志渲染时只解析 [Throwable.toString]，不再追加它自己的构造栈
 * （否则会在链尾多出 `KeelRemoteException.<init>` / `remoteError` 等噪声帧）。
 */
interface RemoteThrowable
