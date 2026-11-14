package moe.lolosia.keel.api.debug

/**
 * 一条逻辑执行栈（`locationId` 序列）。
 *
 * 同步代码用「每线程一份」，协程用「每协程一份」——线程与协程是多对多，
 * 栈挂在线程上会在挂起 / 跨线程恢复时错位。
 *
 * 协程的归属由调度器负责：`WorkerDispatcher.dispatch` 在投递续体时把
 * 该协程的帧表挂到线程局部（见 `Debug.runWithCoroutineFrames`）。
 */
class FrameStack internal constructor(initial: Int = INITIAL) {

    internal var ids: IntArray = IntArray(initial)
        private set

    internal var depth: Int = 0
        private set

    internal fun push(locationId: Int) {
        if (depth == ids.size) ids = ids.copyOf(ids.size * 2)
        ids[depth++] = locationId
    }

    /** 只在栈顶匹配时弹；协程取消 / 跨线程恢复时对不上就忽略。 */
    internal fun pop(locationId: Int) {
        if (depth == 0) return
        if (ids[depth - 1] != locationId) return
        depth--
    }

    internal fun snapshot(): IntArray = ids.copyOf(depth)

    internal companion object {
        private const val INITIAL = 32

        fun of(frames: IntArray): FrameStack = FrameStack(maxOf(INITIAL, frames.size)).also { stack ->
            frames.copyInto(stack.ids)
            stack.depth = frames.size
        }
    }
}
