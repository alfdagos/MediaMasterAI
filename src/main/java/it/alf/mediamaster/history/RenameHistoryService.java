package it.alf.mediamaster.history;

import it.alf.mediamaster.cache.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Persists and manages the rename-operation history backed by the SQLite database.
 *
 * <p>A <em>session</em> is a UUID string created via {@link #startSession()} that
 * groups all rename operations performed during one CLI invocation.
 * Undoing a session restores all affected files to their original paths.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * String sessionId = historyService.startSession();
 * historyService.record(sessionId, hash, source, target, strategyId);
 * ...
 * historyService.undoSession(sessionId); // moves files back
 * }</pre>
 */
public class RenameHistoryService {

    private static final Logger log = LoggerFactory.getLogger(RenameHistoryService.class);

    private final DatabaseManager db;

    /**
     * Creates a {@code RenameHistoryService} backed by the given {@link DatabaseManager}.
     *
     * @param db database manager; must not be {@code null}
     */
    public RenameHistoryService(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "db must not be null");
    }

    // ── Session management ────────────────────────────────────────────────

    /**
     * Creates and returns a new session UUID.
     *
     * @return a fresh UUID string that can be passed to {@link #record}
     */
    public String startSession() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the session ID of the most recently created session in the database.
     *
     * @return the latest session ID, or empty if the history table is empty
     */
    public Optional<String> lastSessionId() {
        String sql = "SELECT session_id FROM rename_history ORDER BY id DESC LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return Optional.of(rs.getString(1));
        } catch (SQLException e) {
            log.error("Failed to read last session ID", e);
        }
        return Optional.empty();
    }

    // ── Recording ─────────────────────────────────────────────────────────

    /**
     * Records a single rename operation in the history.
     *
     * @param operation the operation to persist (id is ignored; a new row is inserted)
     * @return the persisted operation with the database-assigned id
     */
    public RenameOperation record(RenameOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        String sql = """
                INSERT INTO rename_history(session_id, file_hash, original_path, renamed_path, strategy_used)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, operation.sessionId());
            ps.setString(2, operation.fileHash());
            ps.setString(3, operation.originalPath().toString());
            ps.setString(4, operation.renamedPath().toString());
            ps.setString(5, operation.strategyUsed());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0L;
                log.debug("Recorded rename operation id={} session={}", id, operation.sessionId());
                return new RenameOperation(id, operation.sessionId(), operation.fileHash(),
                        operation.originalPath(), operation.renamedPath(),
                        operation.strategyUsed(), operation.renamedAt(), Optional.empty());
            }
        } catch (SQLException e) {
            log.error("Failed to record rename operation", e);
            throw new DatabaseManager.DatabaseException("Failed to record rename operation", e);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Returns all operations for the given session, ordered by id ascending.
     *
     * @param sessionId session UUID
     * @return immutable list of operations (may be empty)
     */
    public List<RenameOperation> findBySession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql = """
                SELECT id, session_id, file_hash, original_path, renamed_path,
                       strategy_used, renamed_at, undone_at
                  FROM rename_history
                 WHERE session_id = ?
                 ORDER BY id
                """;
        return executeQuery(sql, sessionId);
    }

    /**
     * Returns all operations in the history, ordered by id descending.
     *
     * @return immutable list (may be empty)
     */
    public List<RenameOperation> findAll() {
        String sql = """
                SELECT id, session_id, file_hash, original_path, renamed_path,
                       strategy_used, renamed_at, undone_at
                  FROM rename_history
                 ORDER BY id DESC
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return mapRows(rs);
        } catch (SQLException e) {
            log.error("Failed to load history", e);
            return List.of();
        }
    }

    // ── Undo ──────────────────────────────────────────────────────────────

    /**
     * Undoes all active (not previously undone) operations in the given session.
     *
     * <p>Each file is moved back from its renamed path to its original path.
     * Operations are undone in reverse order (last renamed → first restored).
     * For each successful file move the {@code undone_at} column is set.</p>
     *
     * @param sessionId the session to undo
     * @return the number of operations successfully undone
     */
    public int undoSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        List<RenameOperation> ops = findBySession(sessionId).stream()
                .filter(RenameOperation::isActive)
                .sorted(Comparator.comparingLong(RenameOperation::id).reversed())
                .toList();

        log.info("Undoing session {} ({} active operations)", sessionId, ops.size());
        int undone = 0;
        for (RenameOperation op : ops) {
            try {
                move(op.renamedPath(), op.originalPath());
                markUndone(op.id());
                undone++;
                log.info("Undone: {} → {}", op.renamedPath(), op.originalPath());
            } catch (IOException e) {
                log.error("Failed to undo operation id={}: {}", op.id(), e.getMessage());
            }
        }
        log.info("Session {} undo complete: {}/{} operations restored", sessionId, undone, ops.size());
        return undone;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void move(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    private void markUndone(long operationId) {
        String sql = "UPDATE rename_history SET undone_at = strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, operationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to mark operation {} as undone", operationId, e);
        }
    }

    private List<RenameOperation> executeQuery(String sql, String param) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return mapRows(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to query rename history (param={})", param, e);
            return List.of();
        }
    }

    private List<RenameOperation> mapRows(ResultSet rs) throws SQLException {
        List<RenameOperation> result = new ArrayList<>();
        while (rs.next()) {
            long   id           = rs.getLong("id");
            String sessionId    = rs.getString("session_id");
            String fileHash     = rs.getString("file_hash");
            Path   originalPath = Path.of(rs.getString("original_path"));
            Path   renamedPath  = Path.of(rs.getString("renamed_path"));
            String strategy     = rs.getString("strategy_used");
            Instant renamedAt   = Instant.parse(rs.getString("renamed_at"));
            String undoneAtStr  = rs.getString("undone_at");
            Optional<Instant> undoneAt = undoneAtStr == null
                    ? Optional.empty()
                    : Optional.of(Instant.parse(undoneAtStr));
            result.add(new RenameOperation(id, sessionId, fileHash,
                    originalPath, renamedPath, strategy, renamedAt, undoneAt));
        }
        return Collections.unmodifiableList(result);
    }
}
