package com.counters.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateCounterRequestDto(
    val name: String,
    val initialValue: Int = 0,
)
