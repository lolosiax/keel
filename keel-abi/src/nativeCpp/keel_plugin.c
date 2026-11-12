/*
 * 插件侧唯一导出入口与事件泵线程。
 *
 * 关键约束：bootstrap 完全在 C 里执行，不触碰 Kotlin。插件启动是事件泵线程收到的
 * 第一条 START 命令；无论成功失败都回一条 COMPLETE 给 Host，Host 的调用线程
 * 因此从未进入插件运行时。
 */

#include "keel_abi.h"
#include "keel_platform.h"

#include <stdlib.h>
#include <string.h>

#define KEEL_INBOX_CAPACITY 256u

typedef struct KeelPluginState {
    const KeelHostApi *host;
    KeelPluginWorkerMain worker_main;
    KeelPluginApi api;
    KeelPluginHandlers handlers;
    KeelQueue *inbox;
    keel_thread_t thread;
    uint64_t seq;
    /* 下一次定时唤醒的绝对毫秒；0 表示不定时唤醒 */
    uint64_t wake_at_ms;
    int started;
    /* 线程句柄是否已由成功的 keel_thread_start 填充 */
    int thread_started;
} KeelPluginState;

static KeelPluginState g_plugin;

/*
 * 事件泵线程存活标记。
 *
 * bootstrap 在启动线程前置 1，pump_main 退出前清 0；Host 轮询它来决定能否 join。
 * 它只是提示，不承担同步：真正的等待仍由 keel_thread_join 完成。
 */
static volatile int g_pump_alive = 0;

static void release_payload(KeelEvent *event) {
    if (event->release) event->release(event->release_user, event);
    event->payload = NULL;
    event->release = NULL;
    event->release_user = NULL;
}

static void reply_to_host(KeelPluginState *state, const KeelEvent *request, KeelStatus status) {
    if (!state->host) return;

    KeelQueue *host_inbox = state->host->host_inbox;
    if (!host_inbox) return;

    KeelEvent out;
    memset(&out, 0, sizeof(out));
    out.size = (uint32_t)sizeof(out);
    out.kind = KEEL_EVENT_COMPLETE;
    out.seq = ++state->seq;
    out.source = state->api.plugin_id;
    out.target = 0;
    out.correlation = request ? request->correlation : 0;
    out.subject = request ? request->subject : 0;
    out.status = (int32_t)status;

    keel_queue_push(host_inbox, &out, 0);
}

static void start_worker(KeelPluginState *state, const KeelEvent *request) {
    KeelPluginHandlers handlers;
    memset(&handlers, 0, sizeof(handlers));
    handlers.struct_size = (uint32_t)sizeof(handlers);

    KeelStatus status = state->worker_main(&state->api, &handlers);
    if (status == KEEL_OK) {
        state->handlers = handlers;
        state->started = 1;
    }
    reply_to_host(state, request, status);
}

void keel_plugin_set_wakeup(int64_t delay_ms) {
    if (delay_ms < 0) {
        g_plugin.wake_at_ms = 0;
        return;
    }
    g_plugin.wake_at_ms = keel_now_ms() + (uint64_t)delay_ms;
}

/* 本轮 pop 的超时：没有定时任务就永久等待，已到期就立即返回。 */
static uint32_t pump_timeout(const KeelPluginState *state) {
    if (!state->wake_at_ms) return KEEL_WAIT_FOREVER;

    uint64_t now = keel_now_ms();
    if (state->wake_at_ms <= now) return 0;

    uint64_t delta = state->wake_at_ms - now;
    /* 保留 KEEL_WAIT_FOREVER 作哨兵，超大间隔退化为分片等待 */
    return delta >= KEEL_WAIT_FOREVER ? KEEL_WAIT_SLICE_MS : (uint32_t)delta;
}

/*
 * 泵线程的轮询粒度。
 *
 * [keel_plugin_set_wakeup] 可能在任何时刻被别的线程调用（协程续体、定时任务），
 * 而 [keel_queue_pop] 只按进入时的 timeout 等待，不会重新读 `wake_at_ms`；
 * 若此前没有定时任务，pop 会永久阻塞，外部线程设置的唤醒就永远不会被看到。
 * 因此把等待切成小片，每次醒来都重新算一次超时。
 */
#define PUMP_POLL_MS 100u

static KEEL_THREAD_RETURN pump_main(KEEL_THREAD_ARG arg) {
    KeelPluginState *state = (KeelPluginState *)arg;
    KeelEvent event;

    for (;;) {
        uint32_t timeout = pump_timeout(state);
        if (timeout > PUMP_POLL_MS) timeout = PUMP_POLL_MS;

        KeelStatus status = keel_queue_pop(state->inbox, &event, timeout);
        if (status == KEEL_ERR_CLOSED) break;

        if (status == KEEL_ERR_TIMEOUT) {
            /* 只有真正到期才投 TIMER；仅仅是轮询醒来就直接重算 */
            if (state->wake_at_ms && state->wake_at_ms <= keel_now_ms()) {
                state->wake_at_ms = 0;
                if (state->started && state->handlers.on_event) {
                    KeelEvent timer;
                    memset(&timer, 0, sizeof(timer));
                    timer.size = (uint32_t)sizeof(timer);
                    timer.kind = KEEL_EVENT_COMMAND;
                    timer.source = state->api.plugin_id;
                    timer.subject = KEEL_SUBJECT_TIMER;
                    state->handlers.on_event(&timer);
                }
            }
            continue;
        }

        if (status != KEEL_OK) continue;

        if (event.kind == KEEL_EVENT_SHUTDOWN) {
            if (state->started && state->handlers.on_event) state->handlers.on_event(&event);
            release_payload(&event);
            break;
        }

        if (!state->started) {
            if (event.kind == KEEL_EVENT_COMMAND && event.subject == KEEL_SUBJECT_START) {
                start_worker(state, &event);
            } else {
                /* 启动前的其它事件：没有 Kotlin 可以处理，回 FAILED 让 Host 知道 */
                reply_to_host(state, &event, KEEL_ERR_UNSUPPORTED);
            }
        } else if (state->handlers.on_event) {
            state->handlers.on_event(&event);
        }

        release_payload(&event);
    }
    g_pump_alive = 0;
    return KEEL_THREAD_RETURN_VALUE;
}

KEEL_EXPORT KeelStatus keel_plugin_bootstrap(const KeelHostApi *host,
                                            uint64_t plugin_id,
                                            KeelPluginWorkerMain worker_main,
                                            KeelPluginApi *out) {
    if (!host || !worker_main || !out) return KEEL_ERR_INVALID;
    if (host->abi_version != KEEL_ABI_VERSION) return KEEL_ERR_UNSUPPORTED;

    /*
     * 上一轮没有走 shutdown 时，先回收已经退出的线程和 inbox。
     *
     * 仍有存活泵线程时直接拒绝：g_plugin 是二进制级全局，重复 bootstrap 会让两个线程
     * 共享同一份状态、抢同一个 inbox，插件单线程假设当场失效。
     */
    if (g_plugin.inbox != NULL) {
        if (g_pump_alive) return KEEL_ERR_INVALID;
        if (g_plugin.thread_started) {
            keel_thread_join(&g_plugin.thread);
            g_plugin.thread_started = 0;
        }
        keel_queue_destroy(g_plugin.inbox);
        g_plugin.inbox = NULL;
        g_plugin.api.inbox = NULL;
    }

    memset(&g_plugin, 0, sizeof(g_plugin));
    g_plugin.host = host;
    g_plugin.worker_main = worker_main;

    g_plugin.inbox = keel_queue_create(KEEL_INBOX_CAPACITY);
    if (!g_plugin.inbox) return KEEL_ERR_FAILED;

    g_plugin.api.abi_version = KEEL_ABI_VERSION;
    g_plugin.api.struct_size = (uint32_t)sizeof(KeelPluginApi);
    g_plugin.api.plugin_id = plugin_id;
    g_plugin.api.host = host;
    g_plugin.api.inbox = g_plugin.inbox;

    /*
     * 先置存活标记再建线程：否则线程刚启动、pump_main 尚未执行的那一小段窗口里，
     * 另一次 bootstrap 会误判「上一轮已退出」而回收刚创建好的 inbox。
     */
    g_pump_alive = 1;
    if (keel_thread_start(&g_plugin.thread, pump_main, &g_plugin) != 0) {
        g_pump_alive = 0;
        keel_queue_destroy(g_plugin.inbox);
        g_plugin.inbox = NULL;
        g_plugin.api.inbox = NULL;
        return KEEL_ERR_FAILED;
    }
    g_plugin.thread_started = 1;

    *out = g_plugin.api;
    return KEEL_OK;
}

KEEL_EXPORT int keel_plugin_pump_running(void) {
    return g_pump_alive;
}

KEEL_EXPORT int keel_plugin_request_shutdown(void) {
    if (!g_plugin.inbox) return 0;

    KeelEvent event;
    memset(&event, 0, sizeof(event));
    event.size = (uint32_t)sizeof(event);
    event.kind = KEEL_EVENT_SHUTDOWN;
    event.seq = ++g_plugin.seq;
    event.source = g_plugin.api.plugin_id;
    /* 队列满时等一会儿：丢掉 SHUTDOWN 会让泵线程永远不退出 */
    return keel_queue_push(g_plugin.inbox, &event, 1000u) == KEEL_OK ? 1 : 0;
}

KEEL_EXPORT void keel_plugin_join(void) {
    if (!g_plugin.inbox) return;

    if (g_plugin.thread_started) {
        keel_thread_join(&g_plugin.thread);
        g_plugin.thread_started = 0;
    }
    keel_queue_destroy(g_plugin.inbox);
    g_plugin.inbox = NULL;
    g_plugin.api.inbox = NULL;
    g_plugin.wake_at_ms = 0;
    g_plugin.started = 0;
}

KEEL_EXPORT void keel_plugin_shutdown(void) {
    if (!g_plugin.inbox) return;

    keel_plugin_request_shutdown();
    keel_plugin_join();
}
