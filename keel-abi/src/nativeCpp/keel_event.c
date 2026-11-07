/*
 * 事件载荷的默认归还实现。
 *
 * 每个二进制各带一份：发送方把它放进 KeelEvent.release，
 * 接收方复制完载荷后调用，于是 free 落在分配方的 CRT 上。
 */

#include "keel_abi.h"

#include <stdlib.h>

void keel_event_release_default(void *user, const KeelEvent *event) {
    (void)user;
    if (!event || !event->payload) return;
    free(event->payload);
}
