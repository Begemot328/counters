package com.counters

import com.counters.config.DatabaseConfig
import com.counters.db.DatabaseFactory
import com.counters.dto.ErrorResponseDto
import com.counters.dto.ValidationException
import com.counters.dto.appJson
import com.counters.repository.CounterRepository
import com.counters.repository.ExposedCounterRepository
import com.counters.routes.configureCounterRouting
import com.counters.routes.configureSwagger
import com.counters.service.CounterFacade
import com.counters.service.CounterService
import com.counters.service.IdempotencyService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.slf4j.bridge.SLF4JBridgeHandler

fun Application.module() {
    // Liquibase logs through java.util.logging; route it into logback (JVM-global, once).
    if (!SLF4JBridgeHandler.isInstalled()) {
        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()
    }

    val databaseConfig = DatabaseConfig.fromApplicationConfig(environment.config.config("database"))
    val connected = DatabaseFactory.connect(databaseConfig)
    monitor.subscribe(ApplicationStopped) { connected.close() }
    val database = connected.database

    val counterRepository: CounterRepository = ExposedCounterRepository(database)
    val counterService = CounterService(counterRepository)
    val idempotencyService = IdempotencyService(database)
    val counterFacade = CounterFacade(database, counterService, idempotencyService)

    install(CallLogging)
    install(ContentNegotiation) {
        json(appJson)
    }
    install(StatusPages) {
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto(cause.message ?: "invalid request"))
        }
        // What call.receive() throws for a malformed or non-deserializable body.
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("malformed request body"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponseDto("internal error"))
        }
    }

    configureSwagger()
    configureCounterRouting(counterFacade)
}
