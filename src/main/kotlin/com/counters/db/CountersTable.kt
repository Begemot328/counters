package com.counters.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Mirrors the Liquibase-managed table; the schema itself is never created from here. */
object CountersTable : Table("counters") {
    val id = uuid("id")
    val name = varchar("name", 255)
    val value = integer("value").default(0)
    val isDeleted = bool("is_deleted").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_counters_name_active", name) { isDeleted eq false }
    }
}
