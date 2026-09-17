package com.counters.db

import com.counters.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jetbrains.exposed.sql.Database
import liquibase.database.DatabaseFactory as LiquibaseDatabaseFactory

/** A migrated, connected database together with the pool that must be closed on shutdown. */
class ConnectedDatabase(val database: Database, private val dataSource: HikariDataSource) : AutoCloseable {
    override fun close() = dataSource.close()
}

object DatabaseFactory {

    private const val CHANGELOG_PATH = "db/changelog/db.changelog-master.sql"

    fun connect(config: DatabaseConfig): ConnectedDatabase {
        val dataSource = createDataSource(config)
        runMigrations(dataSource, config.schema)
        return ConnectedDatabase(Database.connect(dataSource), dataSource)
    }

    private fun runMigrations(dataSource: HikariDataSource, schema: String) {
        dataSource.connection.use { connection ->
            // Liquibase creates its tracking tables in the connection's default schema
            // before running any changeset, so the schema must already exist.
            connection.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS \"$schema\"") }
            connection.commit()

            val database = LiquibaseDatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(JdbcConnection(connection))
                .apply { defaultSchemaName = schema }
            Liquibase(CHANGELOG_PATH, ClassLoaderResourceAccessor(), database).update()
        }
    }

    private fun createDataSource(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            driverClassName = config.driver
            username = config.user
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }
        return HikariDataSource(hikariConfig)
    }
}
