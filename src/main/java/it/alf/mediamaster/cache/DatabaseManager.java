package it.alf.mediamaster.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;

/**
 * Manages the SQLite database lifecycle for MediaMaster.
 *
 * <p>The database file is stored at {@code ~/.media-master/media-master.db}.
 * On first access the schema is applied automatically from the embedded
 * {@code schema.sql} resource.</p>
 *
 * <p>This class is thread-safe: every call to {@link #getConnection()} returns
 * a fresh JDBC connection. Callers are responsible for closing it via
 * try-with-resources.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * DatabaseManager db = new DatabaseManager();
 * try (Connection conn = db.getConnection()) {
 *     // execute queries
 * }
 * }</pre>
 */
public class DatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    static final String DB_DIR  = ".media-master";
    static final String DB_FILE = "media-master.db";

    private final String jdbcUrl;

    /**
     * Creates a {@code DatabaseManager} using the default database location
     * ({@code ~/.media-master/media-master.db}).
     */
    public DatabaseManager() {
        this(Path.of(System.getProperty("user.home"), DB_DIR, DB_FILE));
    }

    /**
     * Creates a {@code DatabaseManager} using the given database file path.
     * Useful for testing with a temporary directory.
     *
     * @param dbPath absolute path to the SQLite file (created if it does not exist)
     */
    public DatabaseManager(Path dbPath) {
        Objects.requireNonNull(dbPath, "dbPath must not be null");
        ensureParentDirectory(dbPath);
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        initialiseSchema();
        log.info("Database initialised at: {}", dbPath);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Returns a new JDBC {@link Connection} to the database.
     *
     * <p>The caller must close the connection (use try-with-resources).</p>
     *
     * @return open connection
     * @throws DatabaseException if the connection cannot be established
     */
    public Connection getConnection() {
        try {
            Connection conn = DriverManager.getConnection(jdbcUrl);
            // Enable WAL and foreign keys for every connection
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            return conn;
        } catch (SQLException e) {
            throw new DatabaseException("Cannot open database connection: " + jdbcUrl, e);
        }
    }

    /**
     * No-op: connections are per-call, nothing to close at the manager level.
     * Implemented to allow try-with-resources usage of the manager itself in tests.
     */
    @Override
    public void close() {
        // Individual connections are closed by callers; nothing to do here.
    }

    // ── Schema initialisation ──────────────────────────────────────────────

    private void initialiseSchema() {
        String sql = loadSchemaResource();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement  st   = conn.createStatement()) {
            // Enable WAL and foreign keys
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            // Execute each statement (split on ";")
            for (String statement : sql.split(";")) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    st.execute(trimmed);
                }
            }
            log.debug("Schema applied successfully");
        } catch (SQLException e) {
            throw new DatabaseException("Schema initialisation failed", e);
        }
    }

    private String loadSchemaResource() {
        try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
            if (is == null) {
                throw new DatabaseException("schema.sql resource not found on classpath", null);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException("Cannot read schema.sql resource", e);
        }
    }

    private void ensureParentDirectory(Path dbPath) {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new DatabaseException("Cannot create database directory: " + dbPath.getParent(), e);
        }
    }

    // ── Exception ──────────────────────────────────────────────────────────

    /**
     * Unchecked exception wrapping JDBC and I/O errors from {@link DatabaseManager}.
     */
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
