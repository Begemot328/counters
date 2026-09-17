package com.counters

import io.ktor.server.config.MapApplicationConfig
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database for tests. By default a throwaway Testcontainers Postgres (needs Docker); set
 * `TEST_DATABASE_URL` (+ optional `TEST_DATABASE_USER` / `TEST_DATABASE_PASSWORD`) to use an
 * existing instance instead. Either way the application runs against its own schema
 * [SCHEMA], created by the app's migrations, so tests never touch development data.
 */
object TestDatabase {
    const val SCHEMA = "counters_test"

    private class Settings(val url: String, val user: String, val password: String)

    private val settings: Settings by lazy {
        val external = System.getenv("TEST_DATABASE_URL")
        if (external != null) {
            Settings(
                url = external,
                user = System.getenv("TEST_DATABASE_USER") ?: "postgres",
                password = System.getenv("TEST_DATABASE_PASSWORD") ?: "postgres",
            )
        } else {
            val container = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16"))
            container.start()
            Settings(container.jdbcUrl, container.username, container.password)
        }
    }

    private val jdbcUrl: String
        get() = settings.url + (if ('?' in settings.url) "&" else "?") + "currentSchema=$SCHEMA"

    fun applicationConfig() = MapApplicationConfig(
        "database.jdbcUrl" to jdbcUrl,
        "database.schema" to SCHEMA,
        "database.user" to settings.user,
        "database.password" to settings.password,
        "database.driver" to "org.postgresql.Driver",
        "database.maximumPoolSize" to "10",
    )

    /** The same settings as `-P:` overrides for a server booted through EngineMain. */
    fun commandLineOverrides(): Array<String> = arrayOf(
        "-P:database.jdbcUrl=$jdbcUrl",
        "-P:database.schema=$SCHEMA",
        "-P:database.user=${settings.user}",
        "-P:database.password=${settings.password}",
        "-P:database.driver=org.postgresql.Driver",
        "-P:database.maximumPoolSize=10",
    )

    fun connection(): Connection = DriverManager.getConnection(jdbcUrl, settings.user, settings.password)

    /** An Exposed handle on the test schema, for tests that drive services directly. */
    fun exposedDatabase(): Database =
        Database.connect(jdbcUrl, driver = "org.postgresql.Driver", user = settings.user, password = settings.password)

    /** Empties both tables; only valid after the application has run its migrations once. */
    fun truncate() = connection().use { c ->
        c.createStatement().use { it.execute("TRUNCATE counters, idempotency_keys") }
    }
}
