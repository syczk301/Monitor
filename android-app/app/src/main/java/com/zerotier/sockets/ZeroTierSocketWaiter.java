package com.zerotier.sockets;

/** Access the SDK's package-private fd set without reflection or a native fork. */
public final class ZeroTierSocketWaiter {
    private final int fd;
    private final ZeroTierFileDescriptorSet selected = new ZeroTierFileDescriptorSet();

    public ZeroTierSocketWaiter(int fd) {
        if (fd < 0 || fd >= 1024) throw new IllegalArgumentException("Invalid ZeroTier descriptor");
        this.fd = fd;
    }

    /** Caller must serialize this with all other operations on the same fd. */
    public int awaitReady(boolean write, int timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 1000) {
            throw new IllegalArgumentException("Invalid readiness timeout");
        }
        selected.ZERO();
        selected.SET(fd);
        // There is exactly one fd in exactly one set. Use the returned count:
        // the SDK's JNI conversion does not clear non-ready output fd bits.
        return ZeroTierNative.zts_bsd_select(fd + 1,
            write ? null : selected, write ? selected : null, null,
            timeoutMillis / 1000, (timeoutMillis % 1000) * 1000);
    }
}
