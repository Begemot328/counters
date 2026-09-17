package com.counters.domain

import java.time.OffsetDateTime
import java.util.UUID

data class Counter(
    val id: UUID,
    val name: String,
    val value: Int,
    val isDeleted: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

sealed interface CreateResult {
    data class Created(val counter: Counter) : CreateResult
    data class AlreadyExists(val name: String) : CreateResult
}

sealed interface IncrementResult {
    data class Incremented(val counter: Counter) : IncrementResult
    data class NotFound(val name: String) : IncrementResult

    /** The counter is at `Int.MAX_VALUE`: a deterministic client-side conflict, not a server error. */
    data class Overflow(val name: String) : IncrementResult
}
