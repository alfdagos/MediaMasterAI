package it.alf.mediarenamer.batch;

import it.alf.mediarenamer.cache.MetadataCacheService;
import it.alf.mediarenamer.duplicate.DuplicateDetector;
import it.alf.mediarenamer.duplicate.DuplicateDetector.DuplicateGroup;
import it.alf.mediarenamer.history.RenameHistoryService;
import it.alf.mediarenamer.history.RenameOperation;
import it.alf.mediarenamer.rename.RenameOptions;
import it.alf.mediarenamer.rename.RenameProposal;
import it.alf.mediarenamer.rename.RenameResult;
import it.alf.mediarenamer.rename.SmartRenameEngine;
import it.alf.mediarenamer.rename.UndoJournal;
import it.alf.mediarenamer.scanner.MediaScanner;
import it.alf.mediarenamer.scanner.ScanOptions;
import it.alf.mediarenamer.scanner.ScanResult;
import it.alf.mediarenamer.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the full media-renaming pipeline over a directory tree.
 *
 * <p>The pipeline stages in order are:</p>
 * <ol>
 *   <li><b>Scan</b> — discover all media files under the root path</li>
 *   <li><b>Duplicate detection</b> (optional) — group files by SHA-256 hash</li>
 *   <li><b>Rename proposal</b> — use {@link SmartRenameEngine} to generate proposals</li>
 *   <li><b>Execution</b> (skip if dry-run) — perform atomic file moves</li>
 *   <li><b>History recording</b> — persist every rename to the SQLite history table</li>
 * </ol>
 *
 * <p>All dependencies are injected via the constructor to keep the class testable.
 * A convenient factory method {@link #withDefaults()} wires the default implementations.</p>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * BatchRenameProcessor processor = BatchRenameProcessor.withDefaults();
 * BatchOptions options = new BatchOptions(
 *     ScanOptions.defaults(),
 *     RenameOptions.defaults(),
 *     true,   // detect duplicates
 *     false   // execute renames (not dry-run)
 * );
 * BatchResult result = processor.process(Path.of("/home/user/Photos"), options);
 * System.out.println(result.summary());
 * }</pre>
 */
public class BatchRenameProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchRenameProcessor.class);

    private final MediaScanner          scanner;
    private final SmartRenameEngine     engine;
    private final DuplicateDetector     duplicateDetector;
    private final UndoJournal           undoJournal;
    private final RenameHistoryService  historyService;
    private final MetadataCacheService  cacheService;

    /**
     * Creates a {@code BatchRenameProcessor} with all dependencies explicitly provided.
     *
     * @param scanner           media file scanner
     * @param engine            rename proposal + execution engine
     * @param duplicateDetector duplicate grouping service
     * @param undoJournal       NDJSON undo journal (for legacy undo support)
     * @param historyService    DB-backed history service (may be {@code null} to disable)
     * @param cacheService      metadata cache (may be {@code null} to disable)
     */
    public BatchRenameProcessor(MediaScanner scanner,
                                 SmartRenameEngine engine,
                                 DuplicateDetector duplicateDetector,
                                 UndoJournal undoJournal,
                                 RenameHistoryService historyService,
                                 MetadataCacheService cacheService) {
        this.scanner           = Objects.requireNonNull(scanner,           "scanner must not be null");
        this.engine            = Objects.requireNonNull(engine,            "engine must not be null");
        this.duplicateDetector = Objects.requireNonNull(duplicateDetector, "duplicateDetector must not be null");
        this.undoJournal       = Objects.requireNonNull(undoJournal,       "undoJournal must not be null");
        this.historyService    = historyService;
        this.cacheService      = cacheService;
    }

    /**
     * Creates a {@code BatchRenameProcessor} wired with default implementations.
     * Uses the standard database path ({@code ~/.media-renamer/media-renamer.db}).
     *
     * @return a fully-wired processor
     */
    public static BatchRenameProcessor withDefaults() {
        return new BatchRenameProcessor(
                new MediaScanner(),
                new SmartRenameEngine(),
                new DuplicateDetector(),
                new UndoJournal(),
                null,   // DB history optional until DatabaseManager is configured
                null);
    }

    // ── Main pipeline ─────────────────────────────────────────────────────

    /**
     * Runs the full pipeline against the given root directory.
     *
     * @param root    directory to scan; must exist and be readable
     * @param options configuration for this run
     * @return a {@link BatchResult} summary of the completed run
     */
    public BatchResult process(Path root, BatchOptions options) {
        Objects.requireNonNull(root,    "root must not be null");
        Objects.requireNonNull(options, "options must not be null");

        Instant start = Instant.now();
        log.info("BatchRenameProcessor starting on '{}'", root);

        // ── Stage 1: Scan ──────────────────────────────────────────────
        ScanResult scan = scanner.scan(root, options.scanOptions());
        List<it.alf.mediarenamer.model.MediaFile> files = scan.files();
        log.info("Scan complete: {} media files found", files.size());

        // ── Stage 2: Duplicate detection ───────────────────────────────
        List<DuplicateGroup> duplicates = List.of();
        if (options.detectDuplicates() && !files.isEmpty()) {
            duplicates = duplicateDetector.detect(files);
            log.info("Duplicate detection: {} group(s) found", duplicates.size());
        }

        // ── Stage 3: Propose renames ───────────────────────────────────
        List<RenameProposal> proposals = engine.propose(files, options.renameOptions());
        log.info("Generated {} rename proposals", proposals.size());

        // ── Stage 4: Execute / dry-run ─────────────────────────────────
        List<RenameResult> results = engine.executeProposals(proposals, undoJournal, options.dryRun());

        // ── Stage 5: Record history ────────────────────────────────────
        if (!options.dryRun() && historyService != null) {
            persistHistory(results);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Batch complete in {}ms: {} renames, {} skipped, {} errors",
                elapsed.toMillis(),
                countType(results, RenameResult.Success.class),
                countType(results, RenameResult.Skipped.class),
                countType(results, RenameResult.Failed.class));

        return new BatchResult(results, duplicates, files.size(), elapsed);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void persistHistory(List<RenameResult> results) {
        String sessionId = historyService.startSession();
        for (RenameResult result : results) {
            if (result instanceof RenameResult.Success success) {
                try {
                    String hash = FileUtils.sha256(success.targetPath());
                    RenameOperation op = RenameOperation.of(
                            sessionId, hash,
                            success.file().path(),
                            success.targetPath(),
                            success.strategyUsed());
                    historyService.record(op);
                } catch (IOException e) {
                    log.warn("Could not hash renamed file '{}': {}", success.targetPath(), e.getMessage());
                }
            }
        }
    }

    private long countType(List<RenameResult> results, Class<?> type) {
        return results.stream().filter(type::isInstance).count();
    }

    // ── BatchOptions ──────────────────────────────────────────────────────

    /**
     * Configuration for a batch processing run.
     *
     * @param scanOptions       options passed to the {@link MediaScanner}
     * @param renameOptions     options passed to the {@link SmartRenameEngine}
     * @param detectDuplicates  whether to run duplicate detection
     * @param dryRun            when {@code true} renames are proposed but not executed
     */
    public record BatchOptions(
            ScanOptions  scanOptions,
            RenameOptions renameOptions,
            boolean       detectDuplicates,
            boolean       dryRun) {

        public BatchOptions {
            Objects.requireNonNull(scanOptions,   "scanOptions must not be null");
            Objects.requireNonNull(renameOptions, "renameOptions must not be null");
        }

        /** Returns a default {@code BatchOptions} (dry-run off, duplicates off). */
        public static BatchOptions defaults() {
            return new BatchOptions(
                    ScanOptions.defaults(),
                    RenameOptions.defaults(),
                    false,
                    false);
        }
    }

    // ── BatchResult ───────────────────────────────────────────────────────

    /**
     * Summary of a completed batch run.
     *
     * @param renames         list of rename results (one per proposed rename)
     * @param duplicates      detected duplicate groups (empty when detection was disabled)
     * @param filesScanned    total number of media files found during scanning
     * @param elapsed         wall-clock duration of the entire pipeline
     */
    public record BatchResult(
            List<RenameResult>   renames,
            List<DuplicateGroup> duplicates,
            int                  filesScanned,
            Duration             elapsed) {

        public BatchResult {
            Objects.requireNonNull(renames,    "renames must not be null");
            Objects.requireNonNull(duplicates, "duplicates must not be null");
            Objects.requireNonNull(elapsed,    "elapsed must not be null");
        }

        /** Returns the number of files successfully renamed. */
        public long successCount() { return renames.stream().filter(r -> r instanceof RenameResult.Success).count(); }

        /** Returns the number of dry-run proposals logged but not executed. */
        public long dryRunCount()  { return renames.stream().filter(r -> r instanceof RenameResult.DryRun).count(); }

        /** Returns the number of skipped files. */
        public long skippedCount() { return renames.stream().filter(r -> r instanceof RenameResult.Skipped).count(); }

        /** Returns the number of rename failures. */
        public long failedCount()  { return renames.stream().filter(r -> r instanceof RenameResult.Failed).count(); }

        /** Returns a human-readable summary of the batch run. */
        public String summary() {
            return String.format(
                    "Batch complete in %,dms | scanned=%d renamed=%d dry-run=%d skipped=%d failed=%d duplicates=%d",
                    elapsed().toMillis(), filesScanned,
                    successCount(), dryRunCount(), skippedCount(), failedCount(),
                    duplicates.size());
        }
    }
}
