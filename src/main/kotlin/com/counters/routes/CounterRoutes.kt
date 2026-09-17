package com.counters.routes

import com.counters.dto.CreateCounterRequestDto
import com.counters.dto.ErrorResponseDto
import com.counters.dto.ValidationException
import com.counters.dto.validateCounterName
import com.counters.service.CounterFacade
import com.counters.service.IdempotentResult
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

fun Application.configureCounterRouting(facade: CounterFacade) {
    routing {
        route("/counters") {
            post {
                val idempotencyKey = requireIdempotencyKey()
                val request = call.receive<CreateCounterRequestDto>()
                // Validate before the facade reserves the idempotency key for this request.
                validateCounterName(request.name)
                val result = facade.create(request.name, request.initialValue, idempotencyKey)
                if (result is IdempotentResult.Response && result.response.status == HttpStatusCode.Created) {
                    call.response.header(HttpHeaders.Location, "/counters/${request.name}")
                }
                respondIdempotent(result)
            }

            get {
                call.respond(facade.findAll())
            }

            get("/{name}") {
                val name = counterName()
                val counter = facade.findByName(name)
                if (counter == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponseDto("counter '$name' not found"))
                } else {
                    call.respond(counter)
                }
            }

            delete("/{name}") {
                val name = counterName()
                if (facade.delete(name)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponseDto("counter '$name' not found"))
                }
            }

            post("/{name}/increment") {
                val idempotencyKey = requireIdempotencyKey()
                val name = counterName()
                respondIdempotent(facade.increment(name, idempotencyKey))
            }
        }
    }
}

/** The `{name}` path segment, validated against the same rule as on create (400 otherwise). */
private fun RoutingContext.counterName(): String = call.parameters["name"]!!.also(::validateCounterName)

private fun RoutingContext.requireIdempotencyKey(): UUID = call.request.headers[IDEMPOTENCY_KEY_HEADER]
    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: throw ValidationException("missing or invalid $IDEMPOTENCY_KEY_HEADER header (expected a UUID)")

private suspend fun RoutingContext.respondIdempotent(result: IdempotentResult) {
    when (result) {
        is IdempotentResult.Response -> call.respondText(
            result.response.body,
            ContentType.Application.Json,
            result.response.status,
        )
        IdempotentResult.KeyInProgress -> call.respond(
            HttpStatusCode.Conflict,
            ErrorResponseDto("a request with this $IDEMPOTENCY_KEY_HEADER is still processing"),
        )
        IdempotentResult.KeyReusedWithDifferentPayload -> call.respond(
            HttpStatusCode.UnprocessableEntity,
            ErrorResponseDto("this $IDEMPOTENCY_KEY_HEADER was already used with a different request"),
        )
    }
}
