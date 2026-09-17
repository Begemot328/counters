package com.counters

import com.counters.dto.CounterDto
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistenceTest {

    @Test
    fun `counters survive an application restart`() {
        withApp(clean = true) { client ->
            client.createCounter("durable", 40)
            client.increment("durable")
            client.increment("durable")
        }

        // A brand-new application instance (new pool, migrations re-checked) against the same DB.
        withApp(clean = false) { client ->
            assertEquals(CounterDto("durable", 42), client.getCounter("durable").counter())
        }
    }
}
