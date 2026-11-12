package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.classpath.*

/**
 * 经 Host 反向请求读取 `.keel` 资源的 Classpath 实现。
 *
 * 只读 Host 已注册的命名空间：自己的 `.keel`、`of(id)` 指定的插件，或 Host 自身；
 * Host 校验 generation，卸载后读取会以远程异常失败，不会返回旧数据。
 *
 * 所有读取都走事件泵，**只能在 Worker 线程调用**。
 *
 * @param namespace 目标插件 id；为空表示调用插件自己的 `.keel`
 */
internal class HostClasspathClient(
    private val namespace: String?,
) : Classpath {

    override val entries: List<String> get() = list("")

    override fun read(path: String): ByteArray? = request(ClasspathOp.READ, path).bytes

    override fun list(prefix: String): List<String> =
        request(ClasspathOp.LIST, prefix).entries ?: emptyList()

    override fun exists(path: String): Boolean = request(ClasspathOp.EXISTS, path).exists

    override val scheme: String = "keel"

    override val origin: String get() = "keel://${namespace ?: "self"}"

    override fun of(pluginId: String): Classpath = HostClasspathClient(pluginId)

    private fun request(op: ClasspathOp, path: String): ClasspathReply =
        ServiceWire.decode(
            ClasspathReply.serializer(),
            callHost(
                HostSubjects.CLASSPATH.toULong(),
                ServiceWire.encode(
                    ClasspathRequest.serializer(),
                    ClasspathRequest(op = op, path = path, pluginId = namespace),
                ),
            ),
        )
}
