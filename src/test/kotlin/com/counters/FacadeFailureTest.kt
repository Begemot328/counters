package com.counters

import com.counters.domain.Counter
import com.counters.domain.CreateResult
import com.counters.domain.IncrementResult
import com.counters.dto.CounterDto
import com.counters.repository.CounterRepository
import com.counters.service.CounterFacade
import com.counters.service.CounterService
import com.counters.service.IdempotencyService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Drives the facade directly with a repository that blows up, against the real test database,
 * to cover the failure path that cannot be provoked through HTTP.
 */
class FacadeFailureTest {

    @Test
    fun `an exception during the operation releases the key so the same key re-executes on retry`() =
        withApp { client ->
            client.createCounter("fragile", 0)
            val database = TestDatabase.exposedDatabase()
            val facade = CounterFacade(database, CounterService(ExplodingRepository), IdempotencyService(database))
            val key = UUID.randomUUID()

            val failure = runCatching { facade.increment("fragile", key) }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)

            TestDatabase.connection().use { c ->
                c.prepareStatement("SELECT count(*) FROM idempotency_keys WHERE key = ?").use {
                    it.setObject(1, key)
                    val rs = it.executeQuery()
                    rs.next()
                    assertEquals(0, rs.getInt(1), "the reservation was released, not poisoned")
                }
            }

            // The client retries with the very same key against the healthy application.
            assertEquals(CounterDto("fragile", 1), client.increment("fragile", key).counter())
        }

    private object ExplodingRepository : CounterRepository {
        override suspend fun create(name: String, initialValue: Int): CreateResult = boom()
        override suspend fun findByName(name: String): Counter? = boom()
        override suspend fun findAll(): List<Counter> = boom()
        override suspend fun delete(name: String): Boolean = boom()
        override suspend fun increment(name: String): IncrementResult = boom()
        private fun boom(): Nothing = throw IllegalStateException("simulated infrastructure failure")
    }
}
