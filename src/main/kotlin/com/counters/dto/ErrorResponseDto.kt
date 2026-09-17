package com.counters.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponseDto(
    val error: String,
)
