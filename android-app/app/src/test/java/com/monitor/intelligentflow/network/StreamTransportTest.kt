package com.monitor.intelligentflow.network

import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

class StreamTransportTest {
    @Test fun videoCannotReuseApiOrAnotherVideoConnection() {
        val api = EmbeddedTransport.clientBuilder().build()
        val first = isolatedStreamBuilder(EmbeddedTransport.clientBuilder(), true).build()
        val second = isolatedStreamBuilder(EmbeddedTransport.clientBuilder(), true).build()
        assertNotSame(api.connectionPool, first.connectionPool)
        assertNotSame(first.connectionPool, second.connectionPool)
        assertEquals(listOf(Protocol.HTTP_1_1), first.protocols)
        // Network switching must still cancel all streams, even with private pools.
        assertSame(api.dispatcher, first.dispatcher)
        assertTrue(first.interceptors.isNotEmpty())
    }

    @Test fun systemNetworkKeepsItsWorkingProtocolNegotiation() {
        assertEquals(EmbeddedTransport.clientBuilder().build().protocols,
            EmbeddedTransport.streamClientBuilder().build().protocols)
    }
}
