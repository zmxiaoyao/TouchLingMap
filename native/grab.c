/*
 * v2.4.0 独占背屏触摸工具（grab）
 *
 * 用法: grab /dev/input/eventN
 * 1. 打开触摸设备（需 shell 属 input 组，或 root）
 * 2. ioctl(EVIOCGRAB) 独占 —— 独占期间系统与其他应用收不到该设备触摸
 * 3. 流式输出触摸事件（简化文本协议，供 app 端 EvdevTouch 解析）：
 *      X <十进制>   ABS_MT_POSITION_X
 *      Y <十进制>   ABS_MT_POSITION_Y
 *      B <0|1>      按下/抬起（v2.4.6：由 ABS_MT_TRACKING_ID 与 BTN_TOUCH 双源合成，
 *                   兼容"不发 BTN_TOUCH、只用 TRACKING_ID"的现代触摸屏）
 *      S            SYN_REPORT（帧分隔，触发一次 MOVE）
 *      G_READY      独占成功（首行）
 *      G_ERR <原因> 独占失败（首行，调用方回退 getevent 监听）
 * 进程退出（被 kill）时 fd 关闭，内核自动解除独占。
 *
 * 编译（GitHub Actions NDK，静态链接 bionic）：
 *   aarch64-linux-android31-clang -O2 -static -o app/src/main/assets/grab native/grab.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <sys/ioctl.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: grab <device>\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);

    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) {
        printf("G_ERR open\n");
        return 1;
    }
    /* v2.4.8：EVIOCGRAB 重试 —— 快速重开时旧 grab 进程可能尚未退出（EBUSY 竞态），
 * 最多重试 6 次 ×200ms 等旧进程释放（照抄参考实现"启动前先清旧进程"的防护思路）。 */
    int grabbed = -1;
    for (int i = 0; i < 6; i++) {
        grabbed = ioctl(fd, EVIOCGRAB, 1);
        if (grabbed == 0) break;
        usleep(200000);
    }
    if (grabbed < 0) {
        printf("G_ERR grab\n");
        close(fd);
        return 1;
    }
    printf("G_READY\n");

    struct input_event ev;
    int active = 0; /* v2.4.6：当前触点状态（TRACKING_ID/BTN_TOUCH 双源合成，变化才输出） */
    while (read(fd, &ev, sizeof(ev)) == sizeof(ev)) {
        if (ev.type == EV_ABS) {
            if (ev.code == ABS_MT_POSITION_X) {
                printf("X %d\n", ev.value);
            } else if (ev.code == ABS_MT_POSITION_Y) {
                printf("Y %d\n", ev.value);
            } else if (ev.code == ABS_MT_TRACKING_ID) {
                int now = (ev.value != -1) ? 1 : 0;
                if (now != active) {
                    active = now;
                    printf("B %d\n", active);
                }
            }
        } else if (ev.type == EV_KEY) {
            if (ev.code == BTN_TOUCH || ev.code == BTN_TOOL_FINGER) {
                int now = ev.value ? 1 : 0;
                if (now != active) {
                    active = now;
                    printf("B %d\n", active);
                }
            }
        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            printf("S\n");
        }
    }

    ioctl(fd, EVIOCGRAB, 0);
    close(fd);
    return 0;
}