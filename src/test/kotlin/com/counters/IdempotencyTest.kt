package com.counters

import com.counters.dto.CounterDto
import com.counters.dto.ErrorResponseDto
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IdempotencyTest {

    @Test
    fun `retrying a create with the same key replays the 201 without a second counter`() = withApp { client ->
        val key = UUID.randomUUID()

        val first = client.createCounter("replay", 5, key)
        val retry = client.createCounter("replay", 5, key)

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, retry.status)
        assertEquals(first.counter(), retry.counter())
        assertEquals(1, client.listCounters().size)
    }

    @Test
    fun `retrying an increment with the same key replays the value and does not increment again`() = withApp { client ->
        client.createCounter("inc", 0)
        val key = UUID.randomUUID()

        val first = client.increment("inc", key)
        val retry = client.increment("inc", key)

        assertEquals(CounterDto("inc", 1), first.counter())
        assertEquals(CounterDto("inc", 1), retry.counter())
        assertEquals(1, client.getCounter("inc").counter().value)
    }

    @Test
    fun `a recorded error outcome is replayed too`() = withApp { client ->
        client.createCounter("taken", 1)
        val key = UUID.randomUUID()

        val first = client.createCounter("taken", 2, key)
        val retry = client.createCounter("taken", 2, key)

        assertEquals(HttpStatusCode.Conflict, first.status)
        assertEquals(HttpStatusCode.Conflict, retry.status)
        assertEquals(ErrorResponseDto("counter 'taken' already exists"), retry.body())
    }

    @Test
    fun `reusing a key for a different request is 422`() = withApp { client ->
        val key = UUID.randomUUID()
        client.createCounter("first", 0, key)

        val reused = client.createCounter("second", 0, key)

        assertEquals(HttpStatusCode.UnprocessableEntity, reused.status)
        assertEquals(ErrorResponseDto("this Idempotency-Key was already used with a different request"), reused.body())
        assertEquals(HttpStatusCode.NotFound, client.getCounter("second").status)
    }

    @Test
    fun `a key is bound to its operation - reusing it on the other endpoint is 422`() = withApp { client ->
        val createKey = UUID.randomUUID()
        client.createCounter("bound", 5, createKey)

        // Same name, same key — but a different operation: must not replay the 201 create.
        val incrementWithCreateKey = client.increment("bound", createKey)
        assertEquals(HttpStatusCode.UnprocessableEntity, incrementWithCreateKey.status)
        assertEquals(5, client.getCounter("bound").counter().value)

        val incrementKey = UUID.randomUUID()
        client.increment("bound", incrementKey)
        val createWithIncrementKey = client.createCounter("bound", 0, incrementKey)
        assertEquals(HttpStatusCode.UnprocessableEntity, createWithIncrementKey.status)
    }

    @Test
    fun `a key still processing is 409 and nothing is executed`() = withApp { client ->
        client.createCounter("busy", 0)
        val key = UUID.randomUUID()
        insertProcessingRow(key, requestHash = "busy", ageSeconds = 0)

        val response = client.increment("busy", key)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(0, client.getCounter("busy").counter().value)
    }

    @Test
    fun `a stale processing key is taken over and executed exactly once`() = withApp { client ->
        client.createCounter("stale", 0)
        val key = UUID.randomUUID()
        insertProcessingRow(key, requestHash = "stale", ageSeconds = 120)

        val takeover = client.increment("stale", key)
        val replay = client.increment("stale", key)

        assertEquals(CounterDto("stale", 1), takeover.counter())
        assertEquals(CounterDto("stale", 1), replay.counter())
        assertEquals(1, client.getCounter("stale").counter().value)
    }

    private fun insertProcessingRow(key: UUID, requestHash: String, ageSeconds: Int) {
        // Rows are inserted as the increment operation would reserve them.
        val hash = "increment:$requestHash"
        TestDatabase.connection().use { c ->
            c.prepareStatement(
                """
                INSERT INTO idempotency_keys (key, state, request_hash, created_at)
                VALUES (?, 'processing', ?, now() - make_interval(secs => ?))
                """.trimIndent(),
            ).use {
                it.setObject(1, key)
                it.setString(2, hash)
                it.setInt(3, ageSeconds)
                it.executeUpdate()
            }
        }
    }
}
