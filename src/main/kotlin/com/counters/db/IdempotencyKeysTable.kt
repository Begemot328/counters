package com.counters.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Mirrors the Liquibase-managed table; used for column types when binding raw-SQL
 * parameters. [responseBody] is a real Postgres `jsonb` column written with an explicit
 * `::jsonb` cast, which is why it is declared as `text` here rather than via the Exposed DSL.
 */
object IdempotencyKeysTable : Table("idempotency_keys") {
    val key = uuid("key")
    val requestHash = text("request_hash")
    val responseBody = text("response_body").nullable()
    val responseCode = integer("response_code").nullable()
    val state = varchar("state", 16)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(key)
}
