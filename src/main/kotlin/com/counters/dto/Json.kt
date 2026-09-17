package com.counters.dto

import kotlinx.serialization.json.Json

/** The one JSON configuration shared by ContentNegotiation and the stored idempotent responses. */
val appJson: Json = Json {
    encodeDefaults = true
}
