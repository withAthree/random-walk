package com.randomwalk.app.data.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Test-only [SqlDatabase] backed by an in-memory sqlite-jdbc connection.
 *
 * It mirrors the argument handling of [AndroidSqlDatabase] so store and schema
 * tests exercise the same SQL statements on the JVM without a device.
 */
class JdbcSqlDatabase private constructor(
    private val connection: Connection,
) : SqlDatabase {

    override fun exec(sql: String, args: List<Any>) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
        }
    }

    override fun <T> query(sql: String, args: List<Any>, map: (SqlRow) -> T): List<T> {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { resultSet ->
                val results = ArrayList<T>()
                while (resultSet.next()) {
                    results.add(map(JdbcSqlRow(resultSet)))
                }
                return results
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            val result = block()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun close() {
        connection.close()
    }

    private fun bind(statement: PreparedStatement, args: List<Any>) {
        args.forEachIndexed { index, arg ->
            when (arg) {
                is Long -> statement.setLong(index + 1, arg)
                is Int -> statement.setInt(index + 1, arg)
                is Double -> statement.setDouble(index + 1, arg)
                is String -> statement.setString(index + 1, arg)
                is ByteArray -> statement.setBytes(index + 1, arg)
                else -> throw IllegalArgumentException(
                    "unsupported SQL argument type: ${arg::class.java.name}",
                )
            }
        }
    }

    companion object {
        fun inMemory(): JdbcSqlDatabase =
            JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"))
    }
}

private class JdbcSqlRow(private val resultSet: ResultSet) : SqlRow {
    override fun long(column: String): Long = resultSet.getLong(column)
    override fun int(column: String): Int = resultSet.getInt(column)
    override fun double(column: String): Double = resultSet.getDouble(column)
    override fun string(column: String): String = resultSet.getString(column)
    override fun blob(column: String): ByteArray = resultSet.getBytes(column)
}
