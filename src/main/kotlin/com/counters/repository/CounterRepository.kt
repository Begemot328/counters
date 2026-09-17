package com.counters.repository

import com.counters.domain.Counter
import com.counters.domain.CreateResult
import com.counters.domain.IncrementResult

/** Persistence for live (non-soft-deleted) counters. */
interface CounterRepository {
    suspend fun create(name: String, initialValue: Int): CreateResult

    suspend fun findByName(name: String): Counter?

    suspend fun findAll(): List<Counter>

    /** Soft-deletes; returns false if there was no live counter named [name]. */
    suspend fun delete(name: String): Boolean

    /** Atomic `value + 1`; never a read-modify-write. */
    suspend fun increment(name: String): IncrementResult
}
