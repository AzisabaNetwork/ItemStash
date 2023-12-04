package net.azisaba.itemstash.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mariadb.jdbc.Driver;

import java.sql.*;
import java.util.Objects;
import java.util.UUID;

public class DBConnector {
    private static @Nullable HikariDataSource dataSource;

    /**
     * Initializes the data source and pool.
     * @return affected rows when deleting expired stashes
     * @throws SQLException if an error occurs while initializing the pool
     */
    public static int init(@NotNull DatabaseConfig databaseConfig) throws SQLException {
        new Driver();
        HikariConfig config = new HikariConfig();
        if (databaseConfig.driver() != null) {
            config.setDriverClassName(databaseConfig.driver());
        }
        config.setJdbcUrl(databaseConfig.toUrl());
        config.setUsername(databaseConfig.username());
        config.setPassword(databaseConfig.password());
        config.setDataSourceProperties(databaseConfig.properties());
        dataSource = new HikariDataSource(config);
        createTables();
        return getPrepareStatement("DELETE FROM `stashes` WHERE `expires_at` > 0 AND `expires_at` < ?", stmt -> {
            stmt.setLong(1, System.currentTimeMillis());
            return stmt.executeUpdate();
        });
    }

    public static void createTables() throws SQLException {
        runPrepareStatement("CREATE TABLE IF NOT EXISTS `stashes` (\n" +
                "  `uuid` VARCHAR(36) NOT NULL,\n" +
                "  `item` MEDIUMBLOB NOT NULL,\n" +
                "  `expires_at` BIGINT NOT NULL DEFAULT -1,\n" +
                "  `true_amount` INT NOT NULL DEFAULT -1\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;", PreparedStatement::execute);
        runPrepareStatement("CREATE TABLE IF NOT EXISTS `stashes_players` (\n" +
                "  `uuid` VARCHAR(36) NOT NULL PRIMARY KEY,\n" +
                "  `operation_in_progress` BIGINT NOT NULL DEFAULT 0,\n" +
                "  `suppress_notification` TINYINT(1) NOT NULL DEFAULT 0" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;", PreparedStatement::execute);
    }

    /**
     * Returns the data source. Throws an exception if the data source is not initialized using {@link #init(DatabaseConfig)}.
     * @return the data source
     * @throws NullPointerException if the data source is not initialized using {@link #init(DatabaseConfig)}
     */
    @Contract(pure = true)
    @NotNull
    public static HikariDataSource getDataSource() {
        return Objects.requireNonNull(dataSource, "#init was not called");
    }

    @NotNull
    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    @Contract(pure = true)
    public static <R> R use(@NotNull SQLThrowableFunction<Connection, R> action) throws SQLException {
        try (Connection connection = getConnection()) {
            return action.apply(connection);
        }
    }

    @Contract(pure = true)
    public static void use(@NotNull SQLThrowableConsumer<Connection> action) throws SQLException {
        try (Connection connection = getConnection()) {
            action.accept(connection);
        }
    }

    @Contract(pure = true)
    public static void runPrepareStatement(@Language("SQL") @NotNull String sql, @NotNull SQLThrowableConsumer<PreparedStatement> action) throws SQLException {
        use(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                action.accept(statement);
            }
        });
    }

    @Contract(pure = true)
    public static <R> R getPrepareStatement(@Language("SQL") @NotNull String sql, @NotNull SQLThrowableFunction<PreparedStatement, R> action) throws SQLException {
        return use(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                return action.apply(statement);
            }
        });
    }

    @Contract(pure = true)
    public static void useStatement(@NotNull SQLThrowableConsumer<Statement> action) throws SQLException {
        use(connection -> {
            try (Statement statement = connection.createStatement()) {
                action.accept(statement);
            }
        });
    }

    public static boolean isOperationInProgress(@NotNull UUID uuid) throws SQLException {
        return getPrepareStatement("SELECT `operation_in_progress` FROM `stashes_players` WHERE `uuid` = ?", ps -> {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("operation_in_progress") > System.currentTimeMillis();
                } else {
                    return false;
                }
            }
        });
    }

    public static void setOperationInProgress(@NotNull UUID uuid, boolean flag) throws SQLException {
        if (flag) {
            setOperationInProgress(uuid);
        } else {
            setOperationInProgress(uuid, 0);
        }
    }

    public static void setOperationInProgress(@NotNull UUID uuid) throws SQLException {
        setOperationInProgress(uuid, System.currentTimeMillis() + 1000 * 60 * 30);
    }

    public static void setOperationInProgress(@NotNull UUID uuid, long expiresAt) throws SQLException {
        runPrepareStatement("INSERT INTO `stashes_players` (`uuid`, `operation_in_progress`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `operation_in_progress` = VALUES(`operation_in_progress`)", ps -> {
            ps.setString(1, uuid.toString());
            ps.setLong(2, expiresAt);
            ps.executeUpdate();
        });
    }

    public static boolean isSuppressNotification(@NotNull UUID uuid) throws SQLException {
        return getPrepareStatement("SELECT `suppress_notification` FROM `stashes_players` WHERE `uuid` = ?", ps -> {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("suppress_notification");
                } else {
                    return false;
                }
            }
        });
    }

    public static void setSuppressNotification(@NotNull UUID uuid, boolean flag) throws SQLException {
        runPrepareStatement("INSERT INTO `stashes_players` (`uuid`, `suppress_notification`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `suppress_notification` = VALUES(`suppress_notification`)", ps -> {
            ps.setString(1, uuid.toString());
            ps.setBoolean(2, flag);
            ps.executeUpdate();
        });
    }

    /**
     * Closes the data source if it is initialized.
     */
    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
