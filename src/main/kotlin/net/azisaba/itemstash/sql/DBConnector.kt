package net.azisaba.itemstash.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.intellij.lang.annotations.Language
import org.mariadb.jdbc.Driver
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Deque
import java.util.Timer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.fixedRateTimer

object DBConnector {
    private val QUERY_QUEUE: Deque<QueryData<Any?>> = ConcurrentLinkedDeque()
    private var dataSource: HikariDataSource? = null
    private var timer: Timer? = null

    /**
     * Initializes the data source and pool.
     * @return affected rows when deleting expired stashes
     * @throws SQLException if an error occurs while initializing the pool
     */
    fun init(databaseConfig: DatabaseConfig): Int {
        Driver()
        val config = HikariConfig()
        databaseConfig.driver?.let { config.driverClassName = it }
        config.jdbcUrl = databaseConfig.toUrl()
        config.username = databaseConfig.username
        config.password = databaseConfig.password
        config.dataSourceProperties = databaseConfig.properties
        dataSource = HikariDataSource(config)
        createTables()
        timer = fixedRateTimer(daemon = true, initialDelay = 500, period = 500) { processQueue() }
        return getPrepareStatement("DELETE FROM `stashes` WHERE `expires_at` > 0 AND `expires_at` < ?") { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.executeUpdate()
        }
    }

    private fun processQueue() {
        if (QUERY_QUEUE.isEmpty()) return
        try {
            use { connection ->
                while (true) {
                    val data = QUERY_QUEUE.peek() ?: break
                    connection.prepareStatement(data.sql).use { statement ->
                        data.args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
                        statement.executeQuery().use { rs -> data.future.complete(data.resultExtractor(rs)) }
                    }
                    QUERY_QUEUE.remove()
                }
            }
        } catch (_: SQLException) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> queryWithRetry(sql: String, values: List<*>, resultExtractor: (ResultSet) -> T): CompletableFuture<T> {
        val data = QueryData(sql, values, resultExtractor)
        QUERY_QUEUE.add(data as QueryData<Any?>)
        return data.future
    }

    fun executeWithRetry(sql: String, values: List<*>): CompletableFuture<Unit> = queryWithRetry(sql, values) {}

    fun createTables() {
        runPrepareStatement(
            """
            CREATE TABLE IF NOT EXISTS `stashes` (
              `uuid` VARCHAR(36) NOT NULL,
              `item` MEDIUMBLOB NOT NULL,
              `expires_at` BIGINT NOT NULL DEFAULT -1,
              `true_amount` INT NOT NULL DEFAULT -1
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """.trimIndent()
        ) { it.execute() }
        runPrepareStatement(
            """
            CREATE TABLE IF NOT EXISTS `stashes_players` (
              `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
              `operation_in_progress` BIGINT NOT NULL DEFAULT 0,
              `suppress_notification` TINYINT(1) NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """.trimIndent()
        ) { it.execute() }
    }

    /**
     * Returns the data source. Throws an exception if the data source is not initialized using [init].
     */
    fun getDataSource(): HikariDataSource = requireNotNull(dataSource) { "#init was not called" }

    fun getConnection(): Connection = getDataSource().connection

    inline fun <R> use(action: (Connection) -> R): R = getConnection().use(action)

    inline fun runPrepareStatement(@Language("SQL") sql: String, action: (PreparedStatement) -> Unit) {
        getPrepareStatement(sql, action)
    }

    inline fun <R> getPrepareStatement(@Language("SQL") sql: String, action: (PreparedStatement) -> R): R =
        use { connection -> connection.prepareStatement(sql).use(action) }

    inline fun useStatement(action: (Statement) -> Unit) {
        use { connection -> connection.createStatement().use(action) }
    }

    fun isOperationInProgress(uuid: UUID): Boolean =
        getPrepareStatement("SELECT `operation_in_progress` FROM `stashes_players` WHERE `uuid` = ?") { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> rs.next() && rs.getLong("operation_in_progress") > System.currentTimeMillis() }
        }

    fun setOperationInProgress(uuid: UUID, flag: Boolean) {
        if (flag) setOperationInProgress(uuid) else setOperationInProgress(uuid, 0)
    }

    fun setOperationInProgress(uuid: UUID) {
        setOperationInProgress(uuid, System.currentTimeMillis() + 1000 * 60 * 30)
    }

    fun setOperationInProgress(uuid: UUID, expiresAt: Long) {
        runPrepareStatement("INSERT INTO `stashes_players` (`uuid`, `operation_in_progress`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `operation_in_progress` = VALUES(`operation_in_progress`)") { ps ->
            ps.setString(1, uuid.toString())
            ps.setLong(2, expiresAt)
            ps.executeUpdate()
        }
    }

    fun isSuppressNotification(uuid: UUID): Boolean =
        getPrepareStatement("SELECT `suppress_notification` FROM `stashes_players` WHERE `uuid` = ?") { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean("suppress_notification") }
        }

    fun setSuppressNotification(uuid: UUID, flag: Boolean) {
        runPrepareStatement("INSERT INTO `stashes_players` (`uuid`, `suppress_notification`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `suppress_notification` = VALUES(`suppress_notification`)") { ps ->
            ps.setString(1, uuid.toString())
            ps.setBoolean(2, flag)
            ps.executeUpdate()
        }
    }

    /**
     * Closes the data source if it is initialized.
     */
    fun close() {
        timer?.cancel()
        dataSource?.close()
    }
}

/** Reads the whole blob in [column] as a byte array. */
fun ResultSet.getBlobBytes(column: String): ByteArray {
    val blob = getBlob(column)
    return blob.getBytes(1, blob.length().toInt())
}
