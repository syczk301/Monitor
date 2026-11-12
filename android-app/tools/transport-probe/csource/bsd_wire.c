#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <stdlib.h>
#include <stdint.h>
static SOCKET wire;
static struct sockaddr_in peer;
static volatile LONG64 wireIn, wireOut, wireErrors, mailboxMainFull, mailboxRecvFull;
static void *firstMailbox;
static LARGE_INTEGER freq;
static uint64_t maxWireSequence;
void probe_mbox_new(void *q) { InterlockedCompareExchangePointer(&firstMailbox, q, NULL); }
void probe_mbox_full(void *q) {
    InterlockedIncrement64(q == firstMailbox ? &mailboxMainFull : &mailboxRecvFull);
}
uint64_t wire_now_us(void) {
    LARGE_INTEGER value;
    QueryPerformanceCounter(&value);
    return (uint64_t)(value.QuadPart * 1000000 / freq.QuadPart);
}
uint64_t wire_in(void) { return wireIn; }
uint64_t wire_out(void) { return wireOut; }
uint64_t wire_errors(void) { return wireErrors; }
uint64_t mailbox_main_full(void) { return mailboxMainFull; }
uint64_t mailbox_recv_full(void) { return mailboxRecvFull; }
uint64_t wire_missing(void) { return maxWireSequence - (uint64_t)wireIn; }
int wire_start(int localPort, int peerPort) {
    QueryPerformanceFrequency(&freq);
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    wire = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    struct sockaddr_in local = {0};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    local.sin_port = htons((u_short)localPort);
    if (bind(wire, (struct sockaddr *)&local, sizeof(local)))
        return 3;
    int large = 4 * 1024 * 1024;
    setsockopt(wire, SOL_SOCKET, SO_RCVBUF, (const char *)&large, sizeof(large));
    peer.sin_family = AF_INET;
    peer.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    peer.sin_port = htons((u_short)peerPort);
    return 0;
}
int wire_send(const char *data, int len) {
    int n = sendto(wire, data, len, 0, (const struct sockaddr *)&peer, sizeof(peer));
    if (n == len)
        InterlockedIncrement64(&wireOut);
    else
        InterlockedIncrement64(&wireErrors);
    return n;
}
int wire_read(char *data, int cap) {
    fd_set set;
    FD_ZERO(&set);
    FD_SET(wire, &set);
    struct timeval timeout = {0, 1000};
    if (select(0, &set, NULL, NULL, &timeout) > 0) {
        int n = recv(wire, data, cap, 0);
        if (n >= 8) {
            uint64_t seq;
            memcpy(&seq, data, 8);
            if (seq > maxWireSequence)
                maxWireSequence = seq;
            InterlockedIncrement64(&wireIn);
            memmove(data, data + 8, n - 8);
            return n - 8;
        } else
            InterlockedIncrement64(&wireErrors);
        return 0;
    }
    return 0;
}
uint32_t wire_now(void) { return GetTickCount(); }
void wire_sleep(unsigned ms) { Sleep(ms); }
void wire_sleep_precise(unsigned ms) {
    static __declspec(thread) HANDLE timer;
    if (!timer)
        timer = CreateWaitableTimerEx(NULL, NULL, 0x00000002, TIMER_ALL_ACCESS);
    if (!timer) {
        Sleep(ms);
        return;
    }
    LARGE_INTEGER due;
    due.QuadPart = -(LONGLONG)ms * 10000;
    if (SetWaitableTimer(timer, &due, 0, NULL, NULL, FALSE))
        WaitForSingleObject(timer, INFINITE);
    else
        Sleep(ms);
}
void wire_close(void) {
    closesocket(wire);
    WSACleanup();
}
