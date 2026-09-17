package com.counters.config

import io.ktor.server.config.ApplicationConfig

data class DatabaseConfig(
    val jdbcUrl: String,
    val schema: String,
    val user: String,
    val password: String,
    val driver: String,
    val maximumPoolSize: Int,
) {
    companion object {
        fun fromApplicationConfig(config: ApplicationConfig): DatabaseConfig = DatabaseConfig(
            jdbcUrl = config.property("jdbcUrl").getString(),
            schema = config.property("schema").getString(),
            user = config.property("user").getString(),
            password = config.property("password").getString(),
            driver = config.property("driver").getString(),
            maximumPoolSize = config.property("maximumPoolSize").getString().toInt(),
        )
    }
}
