package com.counters

import com.counters.dto.CounterDto
import com.counters.dto.CreateCounterRequestDto
import com.counters.dto.appJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID

const val IDEMPOTENCY_KEY = "Idempotency-Key"

/**
 * Boots the real application module (routes, services, Exposed, Liquibase) against
 * [TestDatabase] and hands the test a JSON-aware client. With [clean] the tables are emptied
 * first; pass `false` to observe state left by a previous application instance.
 */
fun withApp(clean: Boolean = true, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
    environment { config = TestDatabase.applicationConfig() }
    application { module() }
    startApplication()
    if (clean) TestDatabase.truncate()
    val client = createClient {
        install(ContentNegotiation) { json(appJson) }
    }
    block(client)
}

suspend fun HttpClient.createCounter(
    name: String,
    initialValue: Int = 0,
    key: UUID = UUID.randomUUID(),
): HttpResponse = post("/counters") {
    contentType(ContentType.Application.Json)
    header(IDEMPOTENCY_KEY, key.toString())
    setBody(CreateCounterRequestDto(name, initialValue))
}

suspend fun HttpClient.increment(name: String, key: UUID = UUID.randomUUID()): HttpResponse =
    post("/counters/$name/increment") { header(IDEMPOTENCY_KEY, key.toString()) }

suspend fun HttpClient.getCounter(name: String): HttpResponse = get("/counters/$name")

suspend fun HttpClient.listCounters(): List<CounterDto> = get("/counters").body()

suspend fun HttpClient.deleteCounter(name: String): HttpResponse = delete("/counters/$name")

suspend fun HttpResponse.counter(): CounterDto = body()
