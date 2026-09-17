package com.counters.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transactionManager

/**
 * Runs [block] in the caller's active transaction on this database if there is one,
 * otherwise in a new one. This lets a facade wrap several repository calls into a single
 * atomic transaction while each repository method still works standalone.
 *
 * The database is always passed explicitly rather than resolved through Exposed's global
 * default: that default is thread-local-cached and goes stale when more than one Database
 * exists in a JVM (e.g. one per test application).
 */
suspend fun <T> Database.dbQuery(block: suspend Transaction.() -> T): T {
    val current = transactionManager.currentOrNull()
    return if (current != null) current.block() else newSuspendedTransaction(Dispatchers.IO, db = this) { block() }
}
