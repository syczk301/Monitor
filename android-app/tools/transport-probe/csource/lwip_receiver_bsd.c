#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include "lwip/init.h"
#include "lwip/tcp.h"
#include "lwip/netif.h"
#include "lwip/ip4.h"
#include "lwip/tcpip.h"
#include "lwip/sockets.h"
#include "lwip/sys.h"
#include "lwip/stats.h"
int wire_start(int, int);
int wire_send(const char *, int);
int wire_read(char *, int);
uint32_t wire_now(void);
void wire_sleep(unsigned);
void wire_close(void);
uint64_t wire_now_us(void);
uint64_t wire_in(void);
uint64_t wire_out(void);
uint64_t wire_errors(void);
uint64_t mailbox_main_full(void);
uint64_t mailbox_recv_full(void);
uint64_t wire_missing(void);
void wire_sleep_precise(unsigned);
static volatile uint64_t received, calls, emptyReads;
static volatile uint64_t wait_us, read_us;
static unsigned long pbufFailures, inputErrors;
static volatile int initialized, consumerDone;
static uint32_t start;
static int nonblocking;
int zts_errno;
static struct netif iface;
static err_t output(struct netif *netif, struct pbuf *p, const ip4_addr_t *dst) {
    char bytes[65536];
    int count = pbuf_copy_partial(p, bytes, p->tot_len, 0);
    return wire_send(bytes, count) == count ? ERR_OK : ERR_IF;
}
static err_t initif(struct netif *netif) {
    netif->name[0] = 'p';
    netif->name[1] = 'r';
    netif->output = output;
    netif->mtu = 2800;
    netif->flags = NETIF_FLAG_UP | NETIF_FLAG_LINK_UP;
    return ERR_OK;
}
static void init_done(void *arg) {
    ip4_addr_t addr, mask, gw;
    IP4_ADDR(&addr, 10, 0, 0, 2);
    IP4_ADDR(&mask, 255, 255, 255, 0);
    IP4_ADDR(&gw, 0, 0, 0, 0);
    netif_add(&iface, &addr, &mask, &gw, NULL, initif, tcpip_input);
    netif_set_default(&iface);
    netif_set_up(&iface);
    netif_set_link_up(&iface);
    initialized = 1;
}
static void consumer(void *arg) {
    int sock = lwip_socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in remote = {0};
    remote.sin_family = AF_INET;
    remote.sin_port = lwip_htons(8000);
    remote.sin_addr.s_addr = PP_HTONL(LWIP_MAKEU32(10, 0, 0, 1));
    if (lwip_connect(sock, (struct sockaddr *)&remote, sizeof(remote))) {
        fprintf(stderr, "CONNECT ERROR %d\n", zts_errno);
        exit(2);
    }
    printf("CONNECTED socket=%d nonblock=%d TCP_WND=%u MSS=%d scale=%d mailbox=%d\n", sock,
           nonblocking, TCP_WND, TCP_MSS, TCP_RCV_SCALE, MAX_QUEUE_ENTRIES);
    fflush(stdout);
    if (nonblocking)
        lwip_fcntl(sock, F_SETFL, O_NONBLOCK);
    struct timeval timeout = {1, 0};
    lwip_setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    char buf[65536];
    uint64_t offset = 0;
    while (wire_now() - start < 24000) {
        uint64_t readStart = wire_now_us();
        int n = lwip_recv(sock, buf, sizeof(buf), 0);
        read_us += wire_now_us() - readStart;
        if (n > 0) {
            for (int i = 0; i < n; i++)
                if ((unsigned char)buf[i] != (unsigned char)((offset + i) % 65536 % 251)) {
                    fprintf(stderr, "CORRUPT at %llu\n", offset + i);
                    exit(2);
                }
            offset += n;
            received = offset;
            calls++;
        } else if (n < 0 && (zts_errno == EWOULDBLOCK || zts_errno == EAGAIN)) {
            emptyReads++;
            if (nonblocking) {
                uint64_t waitStart = wire_now_us();
                if (nonblocking == 50) {
                    fd_set ready;
                    FD_ZERO(&ready);
                    FD_SET(sock, &ready);
                    struct timeval timeout = {0, 50000};
                    lwip_select(sock + 1, &ready, NULL, NULL, &timeout);
                } else if (nonblocking >= 1000)
                    wire_sleep_precise(nonblocking - 1000);
                else
                    wire_sleep(nonblocking == 1 ? 20 : nonblocking);
                wait_us += wire_now_us() - waitStart;
            }
        } else if (n == 0)
            break;
        else {
            fprintf(stderr, "READ ERROR %d\n", zts_errno);
            break;
        }
    }
    lwip_close(sock);
    consumerDone = 1;
}
int main(int argc, char **argv) {
    if (wire_start(atoi(argv[1]), atoi(argv[2])))
        return 3;
    nonblocking = argc > 3 ? atoi(argv[3]) : 1;
    tcpip_init(init_done, NULL);
    while (!initialized)
        wire_sleep(1);
    start = wire_now();
    uint32_t last = start;
    uint64_t prev = 0, prevCalls = 0, prevEmpty = 0;
    sys_thread_new("receiver", consumer, NULL, 65536, 1);
    while (wire_now() - start < 24000) {
        char data[65536];
        int n = wire_read(data, sizeof(data));
        if (n > 0) {
            struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)n, PBUF_POOL);
            if (p) {
                pbuf_take(p, data, n);
                if (tcpip_input(p, &iface) != ERR_OK) {
                    inputErrors++;
                    pbuf_free(p);
                }
            } else
                pbufFailures++;
        }
        uint32_t now = wire_now();
        if (now - last >= 1000) {
            uint64_t b = received, c = calls, e = emptyReads;
            printf("{\"second\":%.3f,\"Mbps\":%.6f,\"calls\":%llu,\"EAGAIN\":%llu,"
                   "\"cumulativeWaitUs\":%llu,\"cumulativeReadUs\":%llu,\"wireIn\":%llu,"
                   "\"wireOut\":%llu,\"wireErrors\":%llu,\"recvMboxFull\":%llu,\"tcpipMboxFull\":%"
                   "llu,\"pbufFailures\":%lu,\"inputErrors\":%lu}\n",
                   (now - start) / 1000., (b - prev) * 8. / (now - last) / 1000., c - prevCalls,
                   e - prevEmpty, wait_us, read_us, wire_in(), wire_out(), wire_errors(),
                   mailbox_recv_full(), mailbox_main_full(), pbufFailures, inputErrors);
            fflush(stdout);
            prev = b;
            prevCalls = c;
            prevEmpty = e;
            last = now;
        }
    }
    printf("TOTAL %llu bytes done=%d wireIn=%llu wireOut=%llu wireErrors=%llu wireMissing=%llu "
           "recvMboxFull=%llu tcpipMboxFull=%llu pbufFailures=%lu inputErrors=%lu tcpDrop=%u "
           "tcpChecksumErrors=%u tcpErrors=%u\n",
           received, consumerDone, wire_in(), wire_out(), wire_errors(), wire_missing(),
           mailbox_recv_full(), mailbox_main_full(), pbufFailures, inputErrors, lwip_stats.tcp.drop,
           lwip_stats.tcp.chkerr, lwip_stats.tcp.err);
    fflush(stdout);
    return 0;
}
