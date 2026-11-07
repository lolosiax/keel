/*
 * 事件载荷的默认归还实现。
 *
 * 每个二进制各带一份：发送方把它放进 KeelEvent.release，
 * 接收方复制完载荷后调用，于是 free 落在分配方的 CRT 上。
 */

#include "keel_abi.h"

#include <stdlib.h>
#include <string.h>

void keel_event_release_default(void *user, const KeelEvent *event) {
    (void)user;
    if (!event || !event->payload) return;
    free(event->payload);
}

void *keel_payload_alloc(uint32_t size) {
    return malloc(size);
}

void keel_payload_copy(void *dst, const void *src, uint32_t size) {
    if (!dst || !src) return;
    memcpy(dst, src, size);
}

void keel_event_set_default_release(KeelEvent *event) {
    if (!event) return;
    event->release = keel_event_release_default;
}
