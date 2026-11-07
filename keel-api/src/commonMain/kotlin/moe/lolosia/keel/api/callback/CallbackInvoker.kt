package moe.lolosia.keel.api.callback

/**
 * 指向某个调用方回调的发送器：由生成的服务端分发器持有，
 * 在需要通知调用方时把参数编码后投递回去。
 */
interface CallbackInvoker {

    fun invoke(payload: ByteArray)
}
