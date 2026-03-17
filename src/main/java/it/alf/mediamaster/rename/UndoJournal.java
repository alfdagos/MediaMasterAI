package it.alf.mediamaster.rename;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Append-only journal that records every executed rename operation to
 * {@code ~/.media-master/undo-journal.ndjson}.
 *
 * <p>The journal is newline-delimited JSON (NDJSON): each line is a self-contained
 * JSON object representing one {@link Operation}.</p>
 *
 * <p>This class is not thread-safe; callers must synchronise externally when using
 * it from multiple threads.</p>
 */
public class UndoJournal {

    private static final Logger log = LoggerFactory.getLogger(UndoJournal.class);

    static final String JOURNAL_DIR  = ".media-master";
    static final String JOURNAL_FILE = "undo-journal.ndjson";

    private final Path journalPath;

    /**
     * Creates a journal stored in the default location ({@code ~/.media-master/undo-journal.ndjson}).
     */
    public UndoJournal() {
        this(Path.of(System.getProperty("user.home"), JOURNAL_DIR, JOURNAL_FILE));
    }

    /**
     * Creates a journal stored at the given path (used for testing).
     *
     * @param journalPath absolute path to the NDJSON file
     */
    public UndoJournal(Path journalPath) {
        this.journalPath = Objects.requireNonNull(journalPath, "journalPath must not be null");
    }

    // ── Write ──────────────────────────────────────────────────────────────

    /**
     * Appends a rename operation to the journal.
     *
     * @param operation the completed rename operation to record
     * @throws UndoJournalException if the journal file cannot be written
     */
    public void record(Operation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        ensureParentDirectoryExists();

        String line = operation.toJson();
        try (BufferedWriter writer = Files.newBufferedWriter(
                journalPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(line);
            writer.newLine();
            log.debug("Recorded journal entry: session={}, {} → {}",
                    operation.sessionId(),
                    operation.originalPath().getFileName(),
                    operation.renamedPath().getFileName());
        } catch (IOException e) {
            throw new UndoJournalException("Cannot write to undo journal: " + journalPath, e);
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────

    /**
     * Returns all operations recorded in the journal, in the order they were appended.
     *
     * @return unmodifiable list; empty if the journal does not exist or is empty
     */
    public List<Operation> readAll() {
        if (!Files.exists(journalPath)) return List.of();
        try {
            List<String> lines = Files.readAllLines(journalPath, StandardCharsets.UTF_8);
            return lines.stream()
                    .filter(l -> !l.isBlank())
                    .map(Operation::fromJson)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.error("Cannot read undo journal: {}", journalPath, e);
            return List.of();
        }
    }

    /**
     * Returns all operations belonging to the given session, in the order they were appended.
     *
     * @param sessionId UUID returned by the rename command
     * @return unmodifiable list; empty if the session is not found
     */
    public List<Operation> readSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return readAll().stream()
                .filter(op -> sessionId.equals(op.sessionId()))
                .toList();
    }

    /**
     * Returns the session ID of the most recently recorded session, if any.
     */
    public Optional<String> lastSessionId() {
        List<Operation> all = readAll();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast().sessionId());
    }

    // ── Undo ───────────────────────────────────────────────────────────────

    /**
     * Reverts all rename operations belonging to the given session, in reverse order.
     *
     * @param sessionId the session to undo
     * @throws UndoJournalException if the journal cannot be read or a rename fails fatally
     */
    public void undoSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        List<Operation> ops = readSession(sessionId);
        if (ops.isEmpty()) {
            log.warn("No operations found for session: {}", sessionId);
            return;
        }

        log.info("Undoing session {} ({} operations)", sessionId, ops.size());

        // Process in reverse order (newest first)
        List<Operation> reversed = new ArrayList<>(ops);
        Collections.reverse(reversed);

        for (Operation op : reversed) {
            Path renamed   = op.renamedPath();
            Path original  = op.originalPath();
            if (!Files.exists(renamed)) {
                log.warn("Cannot undo: renamed file no longer exists: {}", renamed);
                continue;
            }
            try {
                Files.move(renamed, original, StandardCopyOption.ATOMIC_MOVE);
                log.info("Restored: {} → {}", renamed.getFileName(), original.getFileName());
            } catch (IOException e) {
                log.error("Undo failed for {} → {}: {}", renamed, original, e.getMessage(), e);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void ensureParentDirectoryExists() {
        try {
            Files.createDirectories(journalPath.getParent());
        } catch (IOException e) {
            throw new UndoJournalException(
                    "Cannot create journal directory: " + journalPath.getParent(), e);
        }
    }

    // ── Operation record ───────────────────────────────────────────────────

    /**
     * Immutable record representing a single rename operation in the undo journal.
     *
     * @param timestamp     when the rename was executed
     * @param originalPath  path before renaming
     * @param renamedPath   path after renaming
     * @param strategyUsed  the rename strategy that produced this name
     * @param sessionId     UUID grouping all operations from one CLI invocation
     */
    public record Operation(
            Instant timestamp,
            Path    originalPath,
            Path    renamedPath,
            String  strategyUsed,
            String  sessionId) {

        /** Serialises this operation to a JSON object on one line. */
        public String toJson() {
            // Hand-rolled serialisation to avoid Jackson dependency in this class;
            // Jackson is used elsewhere in the project but keeping this self-contained.
            return String.format(
                    "{\"timestamp\":\"%s\",\"originalPath\":\"%s\",\"renamedPath\":\"%s\"," +
                    "\"strategyUsed\":\"%s\",\"sessionId\":\"%s\"}",
                    timestamp,
                    escape(originalPath.toString()),
                    escape(renamedPath.toString()),
                    escape(strategyUsed),
                    escape(sessionId));
        }

        /** Deserialises an {@link Operation} from a JSON line. Returns {@code null} on parse error. */
        public static Operation fromJson(String json) {
            try {
                String ts       = extractJsonString(json, "timestamp");
                String origPath = extractJsonString(json, "originalPath");
                String renPath  = extractJsonString(json, "renamedPath");
                String strategy = extractJsonString(json, "strategyUsed");
                String session  = extractJsonString(json, "sessionId");
                return new Operation(Instant.parse(ts), Path.of(origPath),
                        Path.of(renPath), strategy, session);
            } catch (Exception e) {
                LoggerFactory.getLogger(UndoJournal.class)
                        .warn("Could not parse journal entry: {}", json);
                return null;
            }
        }

        private static String extractJsonString(String json, String key) {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) throw new IllegalArgumentException("Key not found: " + key);
            start += search.length();
            int end = json.indexOf('"', start);
            if (end < 0) throw new IllegalArgumentException("Unterminated value for key: " + key);
            return json.substring(start, end).replace("\\\\", "\\").replace("\\/", "/");
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // ── Exception ──────────────────────────────────────────────────────────

    /**
     * Thrown when the undo journal cannot be read from or written to.
     */
    public static class UndoJournalException extends RuntimeException {
        public UndoJournalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
