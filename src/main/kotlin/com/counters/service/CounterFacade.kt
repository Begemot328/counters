package com.counters.service

import com.counters.domain.CreateResult
import com.counters.domain.IncrementResult
import com.counters.dto.CounterDto
import com.counters.dto.ErrorResponseDto
import com.counters.dto.appJson
import com.counters.dto.toDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.util.UUID

sealed interface IdempotentResult {
    data class Response(val response: StoredResponse) : IdempotentResult
    data object KeyInProgress : IdempotentResult
    data object KeyReusedWithDifferentPayload : IdempotentResult
}

/**
 * Single entry point for the HTTP layer. Coordinates [CounterService] (business logic) and
 * [IdempotencyService] (key bookkeeping): wraps create/increment in the idempotency protocol
 * and maps their domain results to the HTTP response that gets stored and replayed. Reads and
 * delete are idempotent by nature and pass straight through.
 */
class CounterFacade(
    private val database: Database,
    private val counterService: CounterService,
    private val idempotencyService: IdempotencyService,
) {
    private val log = LoggerFactory.getLogger(CounterFacade::class.java)

    suspend fun create(name: String, initialValue: Int, idempotencyKey: UUID): IdempotentResult =
        withIdempotency(idempotencyKey, requestHash = "create:$name") {
            when (val result = counterService.create(name, initialValue)) {
                is CreateResult.Created -> StoredResponse(
                    HttpStatusCode.Created,
                    appJson.encodeToString(result.counter.toDto()),
                )
                is CreateResult.AlreadyExists -> errorResponse(
                    HttpStatusCode.Conflict,
                    "counter '${result.name}' already exists",
                )
            }
        }

    suspend fun increment(name: String, idempotencyKey: UUID): IdempotentResult =
        withIdempotency(idempotencyKey, requestHash = "increment:$name") {
            when (val result = counterService.increment(name)) {
                is IncrementResult.Incremented -> StoredResponse(
                    HttpStatusCode.OK,
                    appJson.encodeToString(result.counter.toDto()),
                )
                is IncrementResult.NotFound -> errorResponse(
                    HttpStatusCode.NotFound,
                    "counter '${result.name}' not found",
                )
                is IncrementResult.Overflow ->
                    errorResponse(
                        HttpStatusCode.Conflict,
                        "counter '${result.name}' has reached the maximum value ${Int.MAX_VALUE}",
                    )
            }
        }

    suspend fun findByName(name: String): CounterDto? = counterService.findByName(name)?.toDto()

    suspend fun findAll(): List<CounterDto> = counterService.findAll().map { it.toDto() }

    suspend fun delete(name: String): Boolean = counterService.delete(name)

    /**
     * The request hash names the operation as well as the counter, so one key cannot replay a
     * create's response to an increment (or vice versa) — that reuse is a 422.
     */
    private suspend fun withIdempotency(
        key: UUID,
        requestHash: String,
        operation: suspend () -> StoredResponse,
    ): IdempotentResult = when (val outcome = idempotencyService.begin(key, requestHash)) {
        is IdempotencyOutcome.Replay -> IdempotentResult.Response(outcome.response)
        IdempotencyOutcome.InProgress -> IdempotentResult.KeyInProgress
        IdempotencyOutcome.HashMismatch -> IdempotentResult.KeyReusedWithDifferentPayload
        IdempotencyOutcome.Started -> IdempotentResult.Response(execute(key, operation))
    }

    /**
     * The operation and the recording of its result commit in one transaction, so a crash can
     * never leave a committed-but-unrecorded effect (which would make stale-key takeover
     * unsafe). Every deterministic outcome — including 404/409 — is a recorded response. An
     * exception means the transaction rolled back and nothing happened, so the reservation is
     * released (in its own transaction) and the client's retry with the same key re-executes.
     */
    private suspend fun execute(key: UUID, operation: suspend () -> StoredResponse): StoredResponse = try {
        newSuspendedTransaction(Dispatchers.IO, db = database) {
            operation().also { idempotencyService.complete(key, it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { idempotencyService.release(key) }
            .onFailure {
                log.warn(
                    "Could not release Idempotency-Key {} after a failure; it stays 'processing' until taken over",
                    key,
                    it,
                )
            }
        throw e
    }

    private fun errorResponse(status: HttpStatusCode, message: String) =
        StoredResponse(status, appJson.encodeToString(ErrorResponseDto(message)))
}
