package it.alf.mediarenamer.history;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record representing a single file-rename operation stored in the history database.
 *
 * <p>Each operation belongs to a {@link #sessionId()} that groups all renames
 * performed during one CLI invocation. The {@link #undoneAt()} field is
 * {@link Optional#empty()} while the operation is active; it is set when the
 * session is reverted via the {@code undo} command.</p>
 *
 * @param id           surrogate primary key from the database (0 for unsaved records)
 * @param sessionId    UUID grouping all operations from one CLI invocation
 * @param fileHash     SHA-256 hex digest of the file at rename time
 * @param originalPath absolute path before renaming
 * @param renamedPath  absolute path after renaming
 * @param strategyUsed ID of the {@link it.alf.mediarenamer.rename.RenameStrategy} used
 * @param renamedAt    timestamp when the rename was executed
 * @param undoneAt     timestamp when the rename was undone, or empty if still active
 */
public record RenameOperation(
        long             id,
        String           sessionId,
        String           fileHash,
        Path             originalPath,
        Path             renamedPath,
        String           strategyUsed,
        Instant          renamedAt,
        Optional<Instant> undoneAt) {

    public RenameOperation {
        Objects.requireNonNull(sessionId,    "sessionId must not be null");
        Objects.requireNonNull(fileHash,     "fileHash must not be null");
        Objects.requireNonNull(originalPath, "originalPath must not be null");
        Objects.requireNonNull(renamedPath,  "renamedPath must not be null");
        Objects.requireNonNull(strategyUsed, "strategyUsed must not be null");
        Objects.requireNonNull(renamedAt,    "renamedAt must not be null");
        Objects.requireNonNull(undoneAt,     "undoneAt must not be null");
    }

    /** Returns {@code true} if this operation has already been undone. */
    public boolean isUndone() { return undoneAt.isPresent(); }

    /** Returns {@code true} if this operation is still active (not yet undone). */
    public boolean isActive() { return undoneAt.isEmpty(); }

    /**
     * Factory for creating a new unsaved operation (id = 0).
     *
     * @param sessionId    session UUID
     * @param fileHash     SHA-256 of the source file
     * @param originalPath path before the rename
     * @param renamedPath  path after the rename
     * @param strategyUsed strategy identifier
     * @return new operation with {@code id = 0} and current timestamp
     */
    public static RenameOperation of(String sessionId, String fileHash,
                                      Path originalPath, Path renamedPath,
                                      String strategyUsed) {
        return new RenameOperation(0L, sessionId, fileHash,
                originalPath, renamedPath, strategyUsed,
                Instant.now(), Optional.empty());
    }
}
