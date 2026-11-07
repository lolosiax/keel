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
    int started;
} KeelPluginState;

static KeelPluginState g_plugin;

static void release_payload(KeelEvent *event) {
    if (event->release) event->release(event->release_user, event);
    event->payload = NULL;
    event->release = NULL;
    event->release_user = NULL;
}

static void reply_to_host(KeelPluginState *state, const KeelEvent *request, KeelStatus status) {
    if (!state->host || !state->host->host_inbox) return;

    KeelQueue *host_inbox = state->host->host_inbox();
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

static KEEL_THREAD_RETURN pump_main(KEEL_THREAD_ARG arg) {
    KeelPluginState *state = (KeelPluginState *)arg;
    KeelEvent event;

    for (;;) {
        KeelStatus status = keel_queue_pop(state->inbox, &event, KEEL_WAIT_FOREVER);
        if (status == KEEL_ERR_CLOSED) break;
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
    return KEEL_THREAD_RETURN_VALUE;
}

KEEL_EXPORT KeelStatus keel_plugin_bootstrap(const KeelHostApi *host,
                                            KeelPluginWorkerMain worker_main,
                                            KeelPluginApi *out) {
    if (!host || !worker_main || !out) return KEEL_ERR_INVALID;
    if (host->abi_version != KEEL_ABI_VERSION) return KEEL_ERR_UNSUPPORTED;

    memset(&g_plugin, 0, sizeof(g_plugin));
    g_plugin.host = host;
    g_plugin.worker_main = worker_main;

    g_plugin.inbox = keel_queue_create(KEEL_INBOX_CAPACITY);
    if (!g_plugin.inbox) return KEEL_ERR_FAILED;

    g_plugin.api.abi_version = KEEL_ABI_VERSION;
    g_plugin.api.struct_size = (uint32_t)sizeof(KeelPluginApi);
    g_plugin.api.plugin_id = 0; /* 由 Host 在 bootstrap 返回后填入 */
    g_plugin.api.host = host;
    g_plugin.api.inbox = g_plugin.inbox;

    if (keel_thread_start(&g_plugin.thread, pump_main, &g_plugin) != 0) {
        keel_queue_destroy(g_plugin.inbox);
        g_plugin.inbox = NULL;
        return KEEL_ERR_FAILED;
    }

    *out = g_plugin.api;
    return KEEL_OK;
}

KEEL_EXPORT void keel_plugin_shutdown(void) {
    if (!g_plugin.inbox) return;

    KeelEvent event;
    memset(&event, 0, sizeof(event));
    event.size = (uint32_t)sizeof(event);
    event.kind = KEEL_EVENT_SHUTDOWN;
    event.seq = ++g_plugin.seq;
    event.source = g_plugin.api.plugin_id;
    keel_queue_push(g_plugin.inbox, &event, 0);

    keel_thread_join(&g_plugin.thread);

    keel_queue_destroy(g_plugin.inbox);
    g_plugin.inbox = NULL;
    g_plugin.started = 0;
}
