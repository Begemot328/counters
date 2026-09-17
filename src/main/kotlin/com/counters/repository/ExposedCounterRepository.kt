package com.counters.repository

import com.counters.db.CountersTable
import com.counters.db.dbQuery
import com.counters.domain.Counter
import com.counters.domain.CreateResult
import com.counters.domain.IncrementResult
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Reads use the Exposed DSL. The three mutating statements are raw SQL on purpose: they rely on
 * `RETURNING` and on `ON CONFLICT` against a partial index, and their exact shape is the
 * concurrency argument (see README). None of them can raise a constraint error, so they never
 * abort the transaction they share with the idempotency bookkeeping.
 */
class ExposedCounterRepository(private val database: Database) : CounterRepository {

    override suspend fun create(name: String, initialValue: Int): CreateResult = database.dbQuery {
        // ON CONFLICT DO NOTHING instead of catching a unique violation: a failed INSERT would
        // abort the enclosing transaction. The predicate must match the partial unique index.
        querySingle(
            """
            INSERT INTO counters (name, value) VALUES (?, ?)
            ON CONFLICT (name) WHERE NOT is_deleted DO NOTHING
            RETURNING $ALL_COLUMNS
            """.trimIndent(),
            CountersTable.name.columnType to name,
            CountersTable.value.columnType to initialValue,
        ) { it.toCounter() }
            ?.let { CreateResult.Created(it) }
            ?: CreateResult.AlreadyExists(name)
    }

    override suspend fun findByName(name: String): Counter? = database.dbQuery {
        CountersTable.selectAll()
            .where { (CountersTable.name eq name) and (CountersTable.isDeleted eq false) }
            .map { it.toCounter() }
            .singleOrNull()
    }

    override suspend fun findAll(): List<Counter> = database.dbQuery {
        CountersTable.selectAll()
            .where { CountersTable.isDeleted eq false }
            .orderBy(CountersTable.name)
            .map { it.toCounter() }
    }

    override suspend fun delete(name: String): Boolean = database.dbQuery {
        querySingle(
            "UPDATE counters SET is_deleted = true, updated_at = now() WHERE name = ? AND NOT is_deleted RETURNING id",
            CountersTable.name.columnType to name,
        ) { true } ?: false
    }

    override suspend fun increment(name: String): IncrementResult = database.dbQuery {
        // A single atomic UPDATE ... RETURNING: Postgres serializes concurrent updates of the
        // same row, so N concurrent increments apply as N distinct +1 steps and each caller gets
        // back a unique value. The `value <` guard keeps an overflow from raising a SQL error
        // (which would abort the shared transaction); it is told apart from "not found" below.
        val incremented = querySingle(
            """
            UPDATE counters SET value = value + 1, updated_at = now()
            WHERE name = ? AND NOT is_deleted AND value < ${Int.MAX_VALUE}
            RETURNING $ALL_COLUMNS
            """.trimIndent(),
            CountersTable.name.columnType to name,
        ) { it.toCounter() }
        when {
            incremented != null -> IncrementResult.Incremented(incremented)
            findByName(name) != null -> IncrementResult.Overflow(name)
            else -> IncrementResult.NotFound(name)
        }
    }

    /** Runs a mutating statement with a RETURNING clause and maps its single row, or null if none. */
    private fun <T : Any> Transaction.querySingle(
        sql: String,
        vararg args: Pair<org.jetbrains.exposed.sql.IColumnType<*>, Any?>,
        map: (ResultSet) -> T,
    ): T? = exec(sql, args.toList(), explicitStatementType = StatementType.SELECT) { rs ->
        if (rs.next()) map(rs) else null
    }

    private fun ResultRow.toCounter() = Counter(
        id = this[CountersTable.id],
        name = this[CountersTable.name],
        value = this[CountersTable.value],
        isDeleted = this[CountersTable.isDeleted],
        createdAt = this[CountersTable.createdAt],
        updatedAt = this[CountersTable.updatedAt],
    )

    private fun ResultSet.toCounter() = Counter(
        id = getObject("id", UUID::class.java),
        name = getString("name"),
        value = getInt("value"),
        isDeleted = getBoolean("is_deleted"),
        createdAt = getObject("created_at", OffsetDateTime::class.java),
        updatedAt = getObject("updated_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val ALL_COLUMNS = "id, name, value, is_deleted, created_at, updated_at"
    }
}
