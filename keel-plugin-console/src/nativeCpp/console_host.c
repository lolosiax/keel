/*
 * Keel 控制台宿主（helper）。
 *
 * 独立的 console 子系统进程，由插件用 CREATE_NEW_CONSOLE 拉起：窗口与输入输出都归它，
 * 关窗只死这个进程，不会影响 Host。
 *
 * 与插件之间是两条单向命名管道 + 长度前缀帧：
 *   --out <pipe>  helper 读：OUT / TITLE / MODE
 *   --in  <pipe>  helper 写：HELLO / IN / RESIZE / CLOSE
 *
 * 帧头 12 字节小端：magic "KCN1" + version(u16) + type(u16) + length(u32)。
 * 输出字节原样写 CONOUT$（控制台代码页 UTF-8、打开 VT），输入按行读取后转 UTF-8 回传。
 */

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define FRAME_VERSION 1u
#define FRAME_HEADER 12u
#define FRAME_MAX (1u << 20)

#define T_HELLO 1u
#define T_OUT 2u
#define T_IN 3u
#define T_RESIZE 4u
#define T_TITLE 5u
#define T_CLOSE 7u

#define DEFAULT_TITLE L"Keel Console"

static HANDLE g_from_plugin = INVALID_HANDLE_VALUE; /* 读 OUT 帧 */
static HANDLE g_to_plugin = INVALID_HANDLE_VALUE;   /* 写 IN 帧 */
static HANDLE g_console_out = INVALID_HANDLE_VALUE;
static HANDLE g_console_in = INVALID_HANDLE_VALUE;
static CRITICAL_SECTION g_write_lock;
static CRITICAL_SECTION g_screen_lock;
static int g_input_active = 0;

static int g_cols = 0;
static int g_rows = 0;

/* ------------------------------------------------------------------------- */
/* 管道 I/O                                                                  */
/* ------------------------------------------------------------------------- */

static BOOL read_exact(HANDLE handle, void *buffer, DWORD length) {
    BYTE *cursor = (BYTE *)buffer;
    DWORD done = 0;
    while (done < length) {
        DWORD read = 0;
        if (!ReadFile(handle, cursor + done, length - done, &read, NULL)) return FALSE;
        if (read == 0) return FALSE;
        done += read;
    }
    return TRUE;
}

static BOOL write_exact(HANDLE handle, const void *buffer, DWORD length) {
    const BYTE *cursor = (const BYTE *)buffer;
    DWORD done = 0;
    while (done < length) {
        DWORD written = 0;
        if (!WriteFile(handle, cursor + done, length - done, &written, NULL)) return FALSE;
        if (written == 0) return FALSE;
        done += written;
    }
    return TRUE;
}

static BOOL write_frame(unsigned type, const void *payload, DWORD length) {
    BYTE header[FRAME_HEADER];
    header[0] = 'K';
    header[1] = 'C';
    header[2] = 'N';
    header[3] = '1';
    header[4] = (BYTE)(FRAME_VERSION & 0xFF);
    header[5] = (BYTE)((FRAME_VERSION >> 8) & 0xFF);
    header[6] = (BYTE)(type & 0xFF);
    header[7] = (BYTE)((type >> 8) & 0xFF);
    header[8] = (BYTE)(length & 0xFF);
    header[9] = (BYTE)((length >> 8) & 0xFF);
    header[10] = (BYTE)((length >> 16) & 0xFF);
    header[11] = (BYTE)((length >> 24) & 0xFF);

    EnterCriticalSection(&g_write_lock);
    BOOL ok = write_exact(g_to_plugin, header, FRAME_HEADER);
    if (ok && length > 0) ok = write_exact(g_to_plugin, payload, length);
    LeaveCriticalSection(&g_write_lock);
    return ok;
}

static HANDLE connect_pipe(const wchar_t *name, DWORD access) {
    for (int i = 0; i < 100; i++) {
        HANDLE handle = CreateFileW(name, access, 0, NULL, OPEN_EXISTING, 0, NULL);
        if (handle != INVALID_HANDLE_VALUE) return handle;
        Sleep(20);
    }
    return INVALID_HANDLE_VALUE;
}

/* ------------------------------------------------------------------------- */
/* 控制台                                                                    */
/* ------------------------------------------------------------------------- */

static HANDLE open_console(const wchar_t *name, DWORD access) {
    return CreateFileW(name, access, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                       OPEN_EXISTING, 0, NULL);
}

static void write_console(const BYTE *text, DWORD length);
static void screen_erase_line(void);
static void screen_repaint_line(void);

static void write_console(const BYTE *text, DWORD length) {
    if (g_console_out == INVALID_HANDLE_VALUE || length == 0) return;

    /* 控制台是终端：孤立 LF 补成 CRLF */
    BYTE *buffer = (BYTE *)malloc((size_t)length * 2 + 2);
    if (!buffer) return;
    DWORD used = 0;
    BYTE previous = 0;
    for (DWORD i = 0; i < length; i++) {
        BYTE value = text[i];
        if (value == '\n' && previous != '\r') buffer[used++] = '\r';
        buffer[used++] = value;
        previous = value;
    }

    EnterCriticalSection(&g_screen_lock);
    if (g_input_active) screen_erase_line();
    write_exact(g_console_out, buffer, used);
    if (g_input_active) screen_repaint_line();
    LeaveCriticalSection(&g_screen_lock);
    free(buffer);

    CONSOLE_SCREEN_BUFFER_INFO info;
    if (GetConsoleScreenBufferInfo(g_console_out, &info)) {
        int cols = info.srWindow.Right - info.srWindow.Left + 1;
        int rows = info.srWindow.Bottom - info.srWindow.Top + 1;
        if (cols != g_cols || rows != g_rows) {
            g_cols = cols;
            g_rows = rows;
            BYTE payload[4];
            payload[0] = (BYTE)(cols & 0xFF);
            payload[1] = (BYTE)((cols >> 8) & 0xFF);
            payload[2] = (BYTE)(rows & 0xFF);
            payload[3] = (BYTE)((rows >> 8) & 0xFF);
            write_frame(T_RESIZE, payload, 4);
        }
    }
}

static void set_title(const BYTE *text, DWORD length) {
    if (length == 0) {
        SetConsoleTitleW(DEFAULT_TITLE);
        return;
    }
    int chars = MultiByteToWideChar(CP_UTF8, 0, (const char *)text, (int)length, NULL, 0);
    if (chars <= 0) return;
    wchar_t *wide = (wchar_t *)malloc(((size_t)chars + 1) * sizeof(wchar_t));
    if (!wide) return;
    MultiByteToWideChar(CP_UTF8, 0, (const char *)text, (int)length, wide, chars);
    wide[chars] = L'\0';
    SetConsoleTitleW(wide);
    free(wide);
}

static void apply_icon(void) {
    HWND window = GetConsoleWindow();
    HICON icon = LoadIconW(GetModuleHandleW(NULL), MAKEINTRESOURCEW(1));
    if (!window || !icon) return;
    SendMessageW(window, WM_SETICON, ICON_BIG, (LPARAM)icon);
    SendMessageW(window, WM_SETICON, ICON_SMALL, (LPARAM)icon);
}

static void setup_console(int scrollback) {
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);

    DWORD mode = 0;
    if (GetConsoleMode(g_console_out, &mode)) {
        SetConsoleMode(g_console_out,
                       ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING |
                           ENABLE_WRAP_AT_EOL_OUTPUT);
    }
    if (GetConsoleMode(g_console_in, &mode)) {
        /*
         * 不依赖 conhost 的 cooked 模式：某些宿主（Windows Terminal / ConPTY）
         * 下 ENABLE_PROCESSED_INPUT 设不上去，退格和 CRLF 都不会被处理。
         * 这里关掉回显与行输入，由 input_main 自己读按键、自己回显。
         */
        SetConsoleMode(g_console_in, ENABLE_EXTENDED_FLAGS);
    }

    CONSOLE_SCREEN_BUFFER_INFO info;
    if (GetConsoleScreenBufferInfo(g_console_out, &info)) {
        int height = scrollback > 0 ? scrollback : info.dwSize.Y;
        if (height < info.srWindow.Bottom - info.srWindow.Top + 1) {
            height = info.srWindow.Bottom - info.srWindow.Top + 1;
        }
        if (height > 32000) height = 32000;
        COORD size;
        size.X = info.dwSize.X;
        size.Y = (SHORT)height;
        SetConsoleScreenBufferSize(g_console_out, size);

        g_cols = info.srWindow.Right - info.srWindow.Left + 1;
        g_rows = info.srWindow.Bottom - info.srWindow.Top + 1;
    }
}

/* ------------------------------------------------------------------------- */
/* 线程                                                                      */
/* ------------------------------------------------------------------------- */

static DWORD WINAPI output_main(LPVOID argument) {
    (void)argument;
    BYTE header[FRAME_HEADER];

    while (read_exact(g_from_plugin, header, FRAME_HEADER)) {
        if (!(header[0] == 'K' && header[1] == 'C' && header[2] == 'N' && header[3] == '1')) break;
        unsigned version = header[4] | (header[5] << 8);
        if (version != FRAME_VERSION) break;
        unsigned type = header[6] | (header[7] << 8);
        DWORD length = (DWORD)header[8] | ((DWORD)header[9] << 8) |
                       ((DWORD)header[10] << 16) | ((DWORD)header[11] << 24);
        if (length > FRAME_MAX) break;

        BYTE *payload = NULL;
        if (length > 0) {
            payload = (BYTE *)malloc(length);
            if (!payload) break;
            if (!read_exact(g_from_plugin, payload, length)) {
                free(payload);
                break;
            }
        }

        if (type == T_OUT) {
            write_console(payload, length);
        } else if (type == T_TITLE) {
            set_title(payload, length);
        }
        /* MODE / PING 等暂不处理，未知类型直接忽略 */

        free(payload);
    }
    return 0;
}

/* ------------------------------------------------------------------------- */
/* 输入行编辑（不依赖 conhost 的 cooked 模式）                                */
/* ------------------------------------------------------------------------- */

#define LINE_MAX 4096
#define HISTORY_MAX 64

static wchar_t g_line[LINE_MAX];
static int g_line_length;
static int g_line_cursor;

static wchar_t *g_history[HISTORY_MAX];
static int g_history_count;
static int g_history_pos;
static wchar_t g_draft[LINE_MAX];

static const wchar_t *INPUT_PROMPT = L"> ";

static void echo_wide(const wchar_t *text, int length) {
    if (g_console_out == INVALID_HANDLE_VALUE || length <= 0) return;
    DWORD written = 0;
    WriteConsoleW(g_console_out, text, (DWORD)length, &written, NULL);
}

static int is_high_surrogate(wchar_t value) {
    return value >= 0xD800 && value <= 0xDBFF;
}

static int is_low_surrogate(wchar_t value) {
    return value >= 0xDC00 && value <= 0xDFFF;
}

/* 单个 UTF-16 码元在终端里占几列（够用的 East Asian Width 近似） */
static int cell_width(wchar_t value) {
    if (value < 32) return 0;
    if (is_high_surrogate(value)) return 0;
    if (is_low_surrogate(value)) return 2;
    if (value >= 0x1100 &&
        (value <= 0x115F || value == 0x2329 || value == 0x232A ||
         (value >= 0x2E80 && value <= 0xA4CF && value != 0x303F) ||
         (value >= 0xAC00 && value <= 0xD7A3) ||
         (value >= 0xF900 && value <= 0xFAFF) ||
         (value >= 0xFE30 && value <= 0xFE6F) ||
         (value >= 0xFF00 && value <= 0xFF60) ||
         (value >= 0xFFE0 && value <= 0xFFE6) ||
         (value >= 0x20000 && value <= 0x3FFFD))) {
        return 2;
    }
    return 1;
}

static int cells_between(int from, int to) {
    int cells = 0;
    for (int i = from; i < to && i < g_line_length; i++) cells += cell_width(g_line[i]);
    return cells;
}

/* 以下两个函数都要求持有 g_screen_lock */
static void screen_erase_line(void) {
    DWORD written = 0;
    WriteConsoleW(g_console_out, L"\r\x1b[2K", 5, &written, NULL);
}

static void screen_repaint_line(void) {
    DWORD written = 0;
    screen_erase_line();
    WriteConsoleW(g_console_out, INPUT_PROMPT, 2, &written, NULL);
    if (g_line_length > 0) {
        WriteConsoleW(g_console_out, g_line, (DWORD)g_line_length, &written, NULL);
    }
    int after = cells_between(g_line_cursor, g_line_length);
    if (after > 0) {
        wchar_t buffer[32];
        int length = swprintf(buffer, 32, L"\x1b[%dD", after);
        if (length > 0) WriteConsoleW(g_console_out, buffer, (DWORD)length, &written, NULL);
    }
}

static void repaint(void) {
    EnterCriticalSection(&g_screen_lock);
    screen_repaint_line();
    LeaveCriticalSection(&g_screen_lock);
}

static void reset_line(void) {
    g_line_length = 0;
    g_line_cursor = 0;
    g_history_pos = g_history_count;
}

static void load_line(const wchar_t *text) {
    int length = (int)wcslen(text);
    if (length > LINE_MAX - 1) length = LINE_MAX - 1;
    memcpy(g_line, text, (size_t)length * sizeof(wchar_t));
    g_line_length = length;
    g_line_cursor = length;
}

static void history_push(void) {
    if (g_line_length == 0) return;
    g_line[g_line_length] = L'\0';
    if (g_history_count > 0 && wcscmp(g_history[g_history_count - 1], g_line) == 0) return;

    if (g_history_count == HISTORY_MAX) {
        free(g_history[0]);
        memmove(g_history, g_history + 1, (size_t)(HISTORY_MAX - 1) * sizeof(wchar_t *));
        g_history_count--;
    }
    wchar_t *copy = (wchar_t *)malloc(((size_t)g_line_length + 1) * sizeof(wchar_t));
    if (!copy) return;
    memcpy(copy, g_line, ((size_t)g_line_length + 1) * sizeof(wchar_t));
    g_history[g_history_count++] = copy;
}

static void history_up(void) {
    if (g_history_count == 0) return;
    if (g_history_pos == g_history_count) {
        g_line[g_line_length] = L'\0';
        memcpy(g_draft, g_line, ((size_t)g_line_length + 1) * sizeof(wchar_t));
    }
    if (g_history_pos > 0) {
        g_history_pos--;
        load_line(g_history[g_history_pos]);
        repaint();
    }
}

static void history_down(void) {
    if (g_history_pos >= g_history_count) return;
    g_history_pos++;
    load_line(g_history_pos == g_history_count ? g_draft : g_history[g_history_pos]);
    repaint();
}

static void submit_line(void) {
    g_line[g_line_length] = L'\0';
    int bytes = WideCharToMultiByte(CP_UTF8, 0, g_line, g_line_length, NULL, 0, NULL, NULL);
    if (bytes < 0) bytes = 0;
    char *utf8 = (char *)malloc((size_t)bytes + 1);
    if (!utf8) return;
    if (bytes > 0) {
        WideCharToMultiByte(CP_UTF8, 0, g_line, g_line_length, utf8, bytes, NULL, NULL);
    }
    utf8[bytes] = '\n';

    EnterCriticalSection(&g_screen_lock);
    echo_wide(L"\r\n", 2);
    history_push();
    reset_line();
    LeaveCriticalSection(&g_screen_lock);

    write_frame(T_IN, utf8, (DWORD)bytes + 1);
    free(utf8);
}

static void insert_char(wchar_t value) {
    if (g_line_length >= LINE_MAX - 2) return;
    memmove(&g_line[g_line_cursor + 1], &g_line[g_line_cursor],
            (size_t)(g_line_length - g_line_cursor) * sizeof(wchar_t));
    g_line[g_line_cursor++] = value;
    g_line_length++;
    repaint();
}

static void delete_back(void) {
    if (g_line_cursor <= 0) return;
    int remove = 1;
    if (g_line_cursor >= 2 && is_low_surrogate(g_line[g_line_cursor - 1]) &&
        is_high_surrogate(g_line[g_line_cursor - 2])) {
        remove = 2;
    }
    g_line_cursor -= remove;
    memmove(&g_line[g_line_cursor], &g_line[g_line_cursor + remove],
            (size_t)(g_line_length - g_line_cursor - remove) * sizeof(wchar_t));
    g_line_length -= remove;
    repaint();
}

static void delete_forward(void) {
    if (g_line_cursor >= g_line_length) return;
    int remove = 1;
    if (is_high_surrogate(g_line[g_line_cursor]) &&
        g_line_cursor + 1 < g_line_length && is_low_surrogate(g_line[g_line_cursor + 1])) {
        remove = 2;
    }
    memmove(&g_line[g_line_cursor], &g_line[g_line_cursor + remove],
            (size_t)(g_line_length - g_line_cursor - remove) * sizeof(wchar_t));
    g_line_length -= remove;
    repaint();
}

static void delete_word_back(void) {
    int cursor = g_line_cursor;
    while (cursor > 0 && g_line[cursor - 1] == L' ') cursor--;
    while (cursor > 0 && g_line[cursor - 1] != L' ') cursor--;
    if (cursor == g_line_cursor) return;
    memmove(&g_line[cursor], &g_line[g_line_cursor],
            (size_t)(g_line_length - g_line_cursor) * sizeof(wchar_t));
    g_line_length -= g_line_cursor - cursor;
    g_line_cursor = cursor;
    repaint();
}

static void clear_before_cursor(void) {
    if (g_line_cursor == 0) return;
    memmove(g_line, &g_line[g_line_cursor],
            (size_t)(g_line_length - g_line_cursor) * sizeof(wchar_t));
    g_line_length -= g_line_cursor;
    g_line_cursor = 0;
    repaint();
}

static void move_cursor(int delta) {
    if (delta < 0) {
        if (g_line_cursor <= 0) return;
        g_line_cursor--;
        if (g_line_cursor > 0 && is_low_surrogate(g_line[g_line_cursor]) &&
            is_high_surrogate(g_line[g_line_cursor - 1])) {
            g_line_cursor--;
        }
    } else {
        if (g_line_cursor >= g_line_length) return;
        g_line_cursor++;
        if (g_line_cursor < g_line_length && is_high_surrogate(g_line[g_line_cursor - 1]) &&
            is_low_surrogate(g_line[g_line_cursor])) {
            g_line_cursor++;
        }
    }
    repaint();
}

static DWORD WINAPI input_main(LPVOID argument) {
    (void)argument;
    INPUT_RECORD record;

    EnterCriticalSection(&g_screen_lock);
    g_input_active = 1;
    screen_repaint_line();
    LeaveCriticalSection(&g_screen_lock);

    for (;;) {
        DWORD read = 0;
        if (!ReadConsoleInputW(g_console_in, &record, 1, &read) || read == 0) break;
        if (record.EventType != KEY_EVENT || record.Event.KeyEvent.bKeyDown == 0) continue;

        WORD repeat = record.Event.KeyEvent.wRepeatCount ? record.Event.KeyEvent.wRepeatCount : 1;
        WCHAR value = record.Event.KeyEvent.uChar.UnicodeChar;
        WORD key = record.Event.KeyEvent.wVirtualKeyCode;

        for (WORD i = 0; i < repeat; i++) {
            if (key == VK_RETURN || value == L'\r' || value == L'\n') {
                submit_line();
            } else if (key == VK_BACK || value == 8) {
                delete_back();
            } else if (key == VK_DELETE || value == 0x7F) {
                delete_forward();
            } else if (key == VK_LEFT) {
                move_cursor(-1);
            } else if (key == VK_RIGHT) {
                move_cursor(1);
            } else if (key == VK_HOME || value == 1) {
                g_line_cursor = 0;
                repaint();
            } else if (key == VK_END || value == 5) {
                g_line_cursor = g_line_length;
                repaint();
            } else if (key == VK_UP) {
                history_up();
            } else if (key == VK_DOWN) {
                history_down();
            } else if (value == 3) {
                EnterCriticalSection(&g_screen_lock);
                echo_wide(L"^C\r\n", 4);
                reset_line();
                LeaveCriticalSection(&g_screen_lock);
            } else if (value == 21) {
                clear_before_cursor();
            } else if (value == 23) {
                delete_word_back();
            } else if (value >= 32) {
                insert_char(value);
            }
        }
    }

    g_input_active = 0;
    return 0;
}

/* ------------------------------------------------------------------------- */
/* 入口                                                                      */
/* ------------------------------------------------------------------------- */

static const wchar_t *arg_value(int argc, wchar_t **argv, const wchar_t *name) {
    for (int i = 1; i + 1 < argc; i++) {
        if (wcscmp(argv[i], name) == 0) return argv[i + 1];
    }
    return NULL;
}

int wmain(int argc, wchar_t **argv) {
    const wchar_t *out_name = arg_value(argc, argv, L"--out");
    const wchar_t *in_name = arg_value(argc, argv, L"--in");
    const wchar_t *title = arg_value(argc, argv, L"--title");
    const wchar_t *scrollback_text = arg_value(argc, argv, L"--scrollback");
    if (!out_name || !in_name) return 2;

    int scrollback = scrollback_text ? _wtoi(scrollback_text) : 10000;

    InitializeCriticalSection(&g_write_lock);
    InitializeCriticalSection(&g_screen_lock);
    SetConsoleCtrlHandler(NULL, TRUE);

    g_console_out = open_console(L"CONOUT$",
                                 GENERIC_READ | GENERIC_WRITE);
    g_console_in = open_console(L"CONIN$", GENERIC_READ | GENERIC_WRITE);
    if (g_console_out == INVALID_HANDLE_VALUE || g_console_in == INVALID_HANDLE_VALUE) return 3;

    setup_console(scrollback);
    SetConsoleTitleW(title && title[0] ? title : DEFAULT_TITLE);
    apply_icon();

    g_from_plugin = connect_pipe(out_name, GENERIC_READ);
    g_to_plugin = connect_pipe(in_name, GENERIC_WRITE);
    if (g_from_plugin == INVALID_HANDLE_VALUE || g_to_plugin == INVALID_HANDLE_VALUE) return 2;

    BYTE hello[8];
    hello[0] = (BYTE)(g_cols & 0xFF);
    hello[1] = (BYTE)((g_cols >> 8) & 0xFF);
    hello[2] = (BYTE)(g_rows & 0xFF);
    hello[3] = (BYTE)((g_rows >> 8) & 0xFF);
    DWORD pid = GetCurrentProcessId();
    hello[4] = (BYTE)(pid & 0xFF);
    hello[5] = (BYTE)((pid >> 8) & 0xFF);
    hello[6] = (BYTE)((pid >> 16) & 0xFF);
    hello[7] = (BYTE)((pid >> 24) & 0xFF);
    write_frame(T_HELLO, hello, 8);

    HANDLE output_thread = CreateThread(NULL, 0, output_main, NULL, 0, NULL);
    HANDLE input_thread = CreateThread(NULL, 0, input_main, NULL, 0, NULL);
    if (input_thread) CloseHandle(input_thread);

    /* 插件关管道（EOF）后输出线程结束 → 整个 helper 退出；用户关窗则由系统直接终止 */
    if (output_thread) {
        WaitForSingleObject(output_thread, INFINITE);
        CloseHandle(output_thread);
    }
    ExitProcess(0);
    return 0;
}
