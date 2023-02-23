package net.azisaba.itemstash.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mariadb.jdbc.Driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public class DBConnector {
    private static @Nullable HikariDataSource dataSource;

    /**
     * Initializes the data source and pool.
     * @throws SQLException if an error occurs while initializing the pool
     */
    public static void init(@NotNull DatabaseConfig databaseConfig) throws SQLException {
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
    }

    public static void createTables() throws SQLException {
        runPrepareStatement("CREATE TABLE IF NOT EXISTS `stashes` (\n" +
                "  `uuid` VARCHAR(36) NOT NULL,\n" +
                "  `item` MEDIUMBLOB NOT NULL\n" +
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

    /**
     * Closes the data source if it is initialized.
     */
    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
