package com.counters.service

import com.counters.db.IdempotencyKeysTable
import com.counters.db.dbQuery
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import java.util.UUID

sealed interface IdempotencyOutcome {
    /** The caller owns this key (fresh, or taken over from a stale attempt) and must do the real work. */
    data object Started : IdempotencyOutcome

    /** A completed attempt exists for this key with a matching request: replay it as-is. */
    data class Replay(val response: StoredResponse) : IdempotencyOutcome

    /** A matching attempt is still running (a concurrent duplicate). */
    data object InProgress : IdempotencyOutcome

    /** This key was already used for a different request. */
    data object HashMismatch : IdempotencyOutcome
}

/**
 * Idempotency keys: claim first, inspect second. A check-then-insert would let two concurrent
 * callers both see "no key" and both execute; `INSERT ... ON CONFLICT DO NOTHING RETURNING`
 * instead lets the database pick exactly one winner. Everyone else sees the existing row and
 * is told to replay, wait, or reject a mismatched reuse.
 *
 * `processing` rows older than [STALE_AFTER_SECONDS] are considered abandoned (the process
 * died mid-flight) and can be taken over. That is only safe because [CounterFacade] commits
 * the operation and [complete] in one transaction — an abandoned row never has a committed
 * but unrecorded effect behind it.
 */
class IdempotencyService(private val database: Database) {

    suspend fun begin(key: UUID, requestHash: String): IdempotencyOutcome = database.dbQuery {
        val won = exec(
            """
            INSERT INTO idempotency_keys (key, state, request_hash)
            VALUES (?, '$STATE_PROCESSING', ?)
            ON CONFLICT (key) DO NOTHING
            RETURNING key
            """.trimIndent(),
            args = listOf(
                IdempotencyKeysTable.key.columnType to key,
                IdempotencyKeysTable.requestHash.columnType to requestHash,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { rs -> rs.next() } ?: false
        if (won) return@dbQuery IdempotencyOutcome.Started

        val existing = exec(
            "SELECT state, request_hash, response_code, response_body FROM idempotency_keys WHERE key = ?",
            args = listOf(IdempotencyKeysTable.key.columnType to key),
        ) { rs ->
            if (rs.next()) {
                ExistingKey(
                    state = rs.getString("state"),
                    requestHash = rs.getString("request_hash"),
                    responseCode = rs.getInt("response_code"),
                    responseBody = rs.getString("response_body"),
                )
            } else {
                null
            }
        } ?: error("idempotency key $key lost the INSERT race but has no row; processing keys are never deleted")

        when {
            existing.requestHash != requestHash -> IdempotencyOutcome.HashMismatch
            existing.state == STATE_COMPLETED -> IdempotencyOutcome.Replay(
                StoredResponse(HttpStatusCode.fromValue(existing.responseCode), existing.responseBody ?: ""),
            )
            takeOverIfStale(key) -> IdempotencyOutcome.Started
            else -> IdempotencyOutcome.InProgress
        }
    }

    suspend fun complete(key: UUID, response: StoredResponse) {
        database.dbQuery {
            exec(
                """
                UPDATE idempotency_keys
                SET state = '$STATE_COMPLETED', response_code = ?, response_body = ?::jsonb
                WHERE key = ?
                """.trimIndent(),
                args = listOf(
                    IdempotencyKeysTable.responseCode.columnType to response.status.value,
                    IdempotencyKeysTable.responseBody.columnType to response.body,
                    IdempotencyKeysTable.key.columnType to key,
                ),
            )
        }
    }

    /**
     * Gives a reserved key back after the operation failed without a recordable outcome (the
     * transaction rolled back, nothing happened), so the client's retry with the same key
     * executes instead of being told the key is busy or poisoned.
     */
    suspend fun release(key: UUID) {
        database.dbQuery {
            exec(
                "DELETE FROM idempotency_keys WHERE key = ? AND state = '$STATE_PROCESSING'",
                args = listOf(IdempotencyKeysTable.key.columnType to key),
            )
        }
    }

    /** Atomically claims a stale `processing` row; the row lock guarantees a single winner. */
    private fun Transaction.takeOverIfStale(key: UUID): Boolean = exec(
        """
            UPDATE idempotency_keys
            SET created_at = now()
            WHERE key = ? AND state = '$STATE_PROCESSING'
              AND created_at < now() - interval '$STALE_AFTER_SECONDS seconds'
            RETURNING key
        """.trimIndent(),
        args = listOf(IdempotencyKeysTable.key.columnType to key),
        explicitStatementType = StatementType.SELECT,
    ) { rs -> rs.next() } ?: false

    private data class ExistingKey(
        val state: String,
        val requestHash: String,
        val responseCode: Int,
        val responseBody: String?,
    )

    companion object {
        const val STALE_AFTER_SECONDS = 60
        private const val STATE_PROCESSING = "processing"
        private const val STATE_COMPLETED = "completed"
    }
}
