package com.counters

import com.counters.dto.CounterDto
import com.counters.dto.ErrorResponseDto
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountersApiTest {

    @Test
    fun `create returns 201 with Location and the counter`() = withApp { client ->
        val response = client.createCounter("page-views", 5)

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("/counters/page-views", response.headers[HttpHeaders.Location])
        assertEquals(CounterDto("page-views", 5), response.counter())
    }

    @Test
    fun `initialValue defaults to zero`() = withApp { client ->
        val response = client.post("/counters") {
            contentType(ContentType.Application.Json)
            header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
            setBody("""{"name":"defaulted"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(0, response.counter().value)
    }

    @Test
    fun `get returns the counter or 404`() = withApp { client ->
        client.createCounter("a", 1)

        assertEquals(CounterDto("a", 1), client.getCounter("a").counter())

        val missing = client.getCounter("missing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(ErrorResponseDto("counter 'missing' not found"), missing.body())
    }

    @Test
    fun `list returns all live counters sorted by name`() = withApp { client ->
        client.createCounter("b", 2)
        client.createCounter("a", 1)
        client.createCounter("c", 3)
        client.deleteCounter("c")

        assertEquals(listOf(CounterDto("a", 1), CounterDto("b", 2)), client.listCounters())
    }

    @Test
    fun `increment returns the new value and 404 for a missing counter`() = withApp { client ->
        client.createCounter("hits", 10)

        assertEquals(CounterDto("hits", 11), client.increment("hits").counter())
        assertEquals(CounterDto("hits", 12), client.increment("hits").counter())

        val missing = client.increment("missing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(ErrorResponseDto("counter 'missing' not found"), missing.body())
    }

    @Test
    fun `duplicate create is 409`() = withApp { client ->
        client.createCounter("dup", 1)

        val second = client.createCounter("dup", 99)

        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(ErrorResponseDto("counter 'dup' already exists"), second.body())
        assertEquals(1, client.getCounter("dup").counter().value)
    }

    @Test
    fun `delete soft-deletes, repeats are 404, and the name can be created again fresh`() = withApp { client ->
        client.createCounter("temp", 7)
        client.increment("temp")

        assertEquals(HttpStatusCode.NoContent, client.deleteCounter("temp").status)
        assertEquals(HttpStatusCode.NotFound, client.deleteCounter("temp").status)
        assertEquals(HttpStatusCode.NotFound, client.getCounter("temp").status)
        assertEquals(HttpStatusCode.NotFound, client.increment("temp").status)
        assertTrue(client.listCounters().none { it.name == "temp" })

        val recreated = client.createCounter("temp", 1)
        assertEquals(HttpStatusCode.Created, recreated.status)
        assertEquals(CounterDto("temp", 1), recreated.counter())

        TestDatabase.connection().use { c ->
            val rs = c.createStatement().executeQuery("SELECT count(*) FROM counters WHERE name = 'temp'")
            rs.next()
            assertEquals(2, rs.getInt(1), "soft delete keeps the old row")
        }
    }

    @Test
    fun `malformed body is 400`() = withApp { client ->
        val response = client.post("/counters") {
            contentType(ContentType.Application.Json)
            header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
            setBody("""{"name": """)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorResponseDto("malformed request body"), response.body())
    }

    @Test
    fun `invalid name is 400`() = withApp { client ->
        for (bad in listOf("", "has space", "cyrillic-ё", "slash/inside", "x".repeat(256))) {
            val response = client.createCounter(bad)
            assertEquals(HttpStatusCode.BadRequest, response.status, "name '$bad'")
        }
        assertEquals(HttpStatusCode.Created, client.createCounter("Ok_name.v2-x").status)
    }

    @Test
    fun `invalid name in the path is 400 on every route`() = withApp { client ->
        val bad = "has space"

        assertEquals(HttpStatusCode.BadRequest, client.getCounter(bad).status)
        assertEquals(HttpStatusCode.BadRequest, client.deleteCounter(bad).status)
        assertEquals(HttpStatusCode.BadRequest, client.increment(bad).status)
    }

    @Test
    fun `incrementing a counter at Int MAX_VALUE is 409 and is replayed as such`() = withApp { client ->
        client.createCounter("full", Int.MAX_VALUE)
        val key = UUID.randomUUID()

        val first = client.increment("full", key)
        val retry = client.increment("full", key)

        assertEquals(HttpStatusCode.Conflict, first.status)
        assertEquals(HttpStatusCode.Conflict, retry.status)
        assertEquals(
            ErrorResponseDto("counter 'full' has reached the maximum value ${Int.MAX_VALUE}"),
            first.body(),
        )
        assertEquals(Int.MAX_VALUE, client.getCounter("full").counter().value)
    }

    @Test
    fun `missing or malformed Idempotency-Key is 400`() = withApp { client ->
        client.createCounter("k")

        val noHeader = client.post("/counters/k/increment")
        assertEquals(HttpStatusCode.BadRequest, noHeader.status)

        val badHeader = client.post("/counters/k/increment") { header(IDEMPOTENCY_KEY, "not-a-uuid") }
        assertEquals(HttpStatusCode.BadRequest, badHeader.status)

        assertEquals(0, client.getCounter("k").counter().value, "nothing was incremented")
    }
}
