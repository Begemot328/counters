package com.counters.dto

import com.counters.domain.Counter
import kotlinx.serialization.Serializable

@Serializable
data class CounterDto(
    val name: String,
    val value: Int,
)

fun Counter.toDto() = CounterDto(name = name, value = value)
