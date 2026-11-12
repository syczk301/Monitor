package com.monitor.intelligentflow.network

import android.os.ParcelFileDescriptor
import java.io.IOException
import tsbridge.SocketBinder

/** Bind Go's raw socket FD without taking ownership of it. */
internal class AndroidNetworkBinder(private val route: PhysicalNetworkRoute) : SocketBinder {
    @Throws(IOException::class)
    override fun bindSocket(fd: Long) {
        if (fd < 0 || fd > Int.MAX_VALUE) throw IOException("Invalid native socket descriptor")
        // fromFd duplicates the descriptor; closing this wrapper must never
        // close the descriptor owned by the Go transport.
        ParcelFileDescriptor.fromFd(fd.toInt()).use { duplicate ->
            route.bindSocket(duplicate.fileDescriptor)
        }
    }
}
