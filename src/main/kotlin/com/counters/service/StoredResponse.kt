package com.counters.service

import io.ktor.http.HttpStatusCode

/** An HTTP response as recorded for an idempotency key and replayed to retries. */
data class StoredResponse(val status: HttpStatusCode, val body: String)
