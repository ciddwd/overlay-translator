package com.gameocr.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPhaseTimingTest {
    @Test
    fun logMessage_tableDriven_reportsFreshReuseAndFailureCasesWithoutUrlDetails() {
        data class Case(
            val name: String,
            val timing: NetworkPhaseTiming,
            val expected: String,
        )

        listOf(
            Case(
                name = "fresh TLS connection",
                timing = NetworkPhaseTiming(
                    host = "api.example.com",
                    outcome = "http_200",
                    headersMs = 850,
                    dnsMs = 20,
                    connectMs = 180,
                    tlsMs = 90,
                    serverWaitMs = 550,
                    reusedConnection = false,
                    connectAttempts = 1,
                ),
                expected = "[network] host=api.example.com outcome=http_200 headersMs=850 " +
                    "dnsMs=20 connectMs=180 tlsMs=90 serverWaitMs=550 reused=false attempts=1",
            ),
            Case(
                name = "pooled connection",
                timing = NetworkPhaseTiming(
                    host = "api.example.com",
                    outcome = "http_200",
                    headersMs = 120,
                    dnsMs = 0,
                    connectMs = 0,
                    tlsMs = 0,
                    serverWaitMs = 118,
                    reusedConnection = true,
                    connectAttempts = 0,
                ),
                expected = "[network] host=api.example.com outcome=http_200 headersMs=120 " +
                    "dnsMs=0 connectMs=0 tlsMs=0 serverWaitMs=118 reused=true attempts=0",
            ),
            Case(
                name = "connect failure after alternate routes",
                timing = NetworkPhaseTiming(
                    host = "api.example.com",
                    outcome = "failed_ConnectException",
                    headersMs = 15_000,
                    dnsMs = 5,
                    connectMs = 14_990,
                    tlsMs = 0,
                    serverWaitMs = 0,
                    reusedConnection = false,
                    connectAttempts = 2,
                ),
                expected = "[network] host=api.example.com outcome=failed_ConnectException headersMs=15000 " +
                    "dnsMs=5 connectMs=14990 tlsMs=0 serverWaitMs=0 reused=false attempts=2",
            ),
            Case(
                name = "first request after screen wake",
                timing = NetworkPhaseTiming(
                    host = "api.example.com",
                    outcome = "http_200",
                    headersMs = 2_300,
                    dnsMs = 15,
                    connectMs = 130,
                    tlsMs = 80,
                    serverWaitMs = 2_100,
                    reusedConnection = false,
                    connectAttempts = 1,
                    firstRequestAfterWake = true,
                    wakeAgeMs = 420,
                    screenOffDurationMs = 90_000,
                    idleConnectionsCleared = 2,
                ),
                expected = "[network] host=api.example.com outcome=http_200 headersMs=2300 " +
                    "dnsMs=15 connectMs=130 tlsMs=80 serverWaitMs=2100 reused=false attempts=1 " +
                    "firstAfterWake=true wakeAgeMs=420 screenOffMs=90000 evictedIdle=2",
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.timing.logMessage())
        }
    }
}
