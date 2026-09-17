package com.counters.service

import com.counters.domain.Counter
import com.counters.domain.CreateResult
import com.counters.domain.IncrementResult
import com.counters.repository.CounterRepository

/**
 * Counter business logic. Knows nothing about HTTP or idempotency — that is [CounterFacade]'s
 * job. Today the rules are fully expressed by the persistence contract (uniqueness of live
 * names, atomic increment), so this layer is thin; any future rule (limits, quotas, naming
 * policy beyond input validation) belongs here.
 */
class CounterService(private val repository: CounterRepository) {

    suspend fun create(name: String, initialValue: Int): CreateResult = repository.create(name, initialValue)

    suspend fun increment(name: String): IncrementResult = repository.increment(name)

    suspend fun findByName(name: String): Counter? = repository.findByName(name)

    suspend fun findAll(): List<Counter> = repository.findAll()

    suspend fun delete(name: String): Boolean = repository.delete(name)
}
