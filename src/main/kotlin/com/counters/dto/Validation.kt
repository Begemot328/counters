package com.counters.dto

class ValidationException(message: String) : RuntimeException(message)

private val COUNTER_NAME_PATTERN = Regex("^[A-Za-z0-9_.-]{1,255}$")

/** Names are restricted to a URL-safe charset so they are always addressable as a path segment. */
fun validateCounterName(name: String) {
    if (!COUNTER_NAME_PATTERN.matches(name)) {
        throw ValidationException("name must be 1-255 characters of [A-Za-z0-9_.-]")
    }
}
