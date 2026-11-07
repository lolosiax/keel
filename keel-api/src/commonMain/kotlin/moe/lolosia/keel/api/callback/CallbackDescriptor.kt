package moe.lolosia.keel.api.callback

/**
 * 本地回调描述符：由 KSP 生成，负责把跨模块传来的字节解码成函数参数并调用用户 lambda。
 *
 * 回调永远是**本地对象**；跨模块只传它的数字 id。
 */
fun interface CallbackDescriptor {

    fun invoke(payload: ByteArray)
}
