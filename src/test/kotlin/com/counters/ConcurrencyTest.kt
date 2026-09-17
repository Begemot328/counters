package com.counters

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The task's concurrency guarantees, exercised through the HTTP API with many requests in
 * flight at once against a real Postgres.
 */
class ConcurrencyTest {

    @Test
    fun `N concurrent increments add exactly N and every caller sees a distinct value`() = withApp { client ->
        val n = 200
        client.createCounter("hits", 0)

        val responses = coroutineScope {
            (1..n).map { async { client.increment("hits") } }.awaitAll()
        }

        assertEquals(n, responses.count { it.status == HttpStatusCode.OK }, "every increment succeeded")
        val values = responses.map { it.counter().value }
        assertEquals((1..n).toSet(), values.toSet(), "each response carries a unique value, none lost")
        assertEquals(n, client.getCounter("hits").counter().value, "final value is exactly +N")
    }

    @Test
    fun `concurrent creates of the same name succeed exactly once`() = withApp { client ->
        val m = 50

        val statuses = coroutineScope {
            (1..m).map { i -> async { client.createCounter("race", initialValue = i).status } }.awaitAll()
        }

        assertEquals(1, statuses.count { it == HttpStatusCode.Created }, "exactly one 201")
        assertEquals(m - 1, statuses.count { it == HttpStatusCode.Conflict }, "all others 409")
        assertEquals(1, client.listCounters().count { it.name == "race" }, "a single live row")
    }

    @Test
    fun `concurrent retries with one Idempotency-Key increment once`() = withApp { client ->
        client.createCounter("once", 0)
        val key = java.util.UUID.randomUUID()

        val responses = coroutineScope {
            (1..20).map { async { client.increment("once", key) } }.awaitAll()
        }

        // Every caller gets either the real result, its replay, or 409 while the winner is
        // still in flight — but the counter moves exactly once.
        val statuses = responses.map { it.status }.toSet()
        assertTrue(
            statuses.all { it == HttpStatusCode.OK || it == HttpStatusCode.Conflict },
            "unexpected statuses: $statuses",
        )
        assertEquals(1, client.getCounter("once").counter().value)
        responses.filter { it.status == HttpStatusCode.OK }.forEach { assertEquals(1, it.counter().value) }
    }
}
