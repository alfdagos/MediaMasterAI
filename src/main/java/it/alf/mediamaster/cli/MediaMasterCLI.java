package it.alf.mediamaster.cli;

import it.alf.mediamaster.cache.DatabaseManager;
import it.alf.mediamaster.cache.MetadataCacheService;
import it.alf.mediamaster.duplicate.DuplicateDetector;
import it.alf.mediamaster.duplicate.DuplicateDetector.DuplicateGroup;
import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.enrichment.MetadataEnrichmentService;
import it.alf.mediamaster.metadata.AudioMetadataExtractor;
import it.alf.mediamaster.metadata.ImageMetadataExtractor;
import it.alf.mediamaster.metadata.VideoMetadataExtractor;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.rename.*;
import it.alf.mediamaster.scanner.MediaScanner;
import it.alf.mediamaster.scanner.ScanOptions;
import it.alf.mediamaster.scanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * MediaMaster CLI — entry point for all subcommands.
 *
 * <p>Exit codes:</p>
 * <ul>
 *   <li>0 — success (all operations completed without error)</li>
 *   <li>1 — partial failure (some files skipped or failed)</li>
 *   <li>2 — fatal error (scan or configuration failure)</li>
 * </ul>
 *
 * <p>Usage examples:</p>
 * <pre>{@code
 *   media-master scan    ~/Pictures --depth 5
 *   media-master preview ~/Pictures --strategy DATE_LOCATION
 *   media-master rename  ~/Pictures --strategy DATE_LOCATION --yes
 *   media-master undo
 * }</pre>
 */
@Command(
    name        = "media-master",
    description = "Renames media files based on extracted and enriched metadata.",
    version     = "1.0.0",
    mixinStandardHelpOptions = true,
    subcommands = {
        MediaMasterCLI.ScanCommand.class,
        MediaMasterCLI.PreviewCommand.class,
        MediaMasterCLI.RenameCommand.class,
        MediaMasterCLI.UndoCommand.class,
        MediaMasterCLI.DuplicatesCommand.class,
        MediaMasterCLI.CacheClearCommand.class
    }
)
public class MediaMasterCLI implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MediaMasterCLI.class);

    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) {
        int exit = new CommandLine(new MediaMasterCLI())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    log.error("Fatal error: {}", ex.getMessage(), ex);
                    ConsoleOutput.error("Fatal error: " + ex.getMessage());
                    return 2;
                })
                .execute(args);
        System.exit(exit);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Subcommand: scan
    // ═══════════════════════════════════════════════════════════════════════

    @Command(
        name        = "scan",
        description = "Recursively scans a directory and reports discovered media files.",
        mixinStandardHelpOptions = true
    )
    static class ScanCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<directory>",
                description = "Root directory to scan.")
        Path root;

        @Option(names = {"--depth", "-d"}, defaultValue = "20",
                description = "Maximum directory depth (default: 20).")
        int depth;

        @Option(names = "--include-hidden",
                description = "Include hidden files and directories.")
        boolean includeHidden;

        @Option(names = "--follow-links",
                description = "Follow symbolic links.")
        boolean followLinks;

        @Override
        public Integer call() {
            MediaScanner scanner = new MediaScanner();
            ScanOptions  options = ScanOptions.defaults()
                    .withMaxDepth(depth)
                    .withIncludeHidden(includeHidden)
                    .withFollowLinks(followLinks);

            log.info("Scanning: {}", root);
            ConsoleOutput.info("Scanning: " + root);

            try {
                ScanResult result = scanner.scan(root, options);
                ConsoleOutput.print(result.summary());
                return 0;
            } catch (Exception e) {
                log.error("Scan failed: {}", e.getMessage(), e);
                ConsoleOutput.error("Scan failed: " + e.getMessage());
                return 2;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Subcommand: preview
    // ═══════════════════════════════════════════════════════════════════════

    @Command(
        name        = "preview",
        description = "Scans and shows proposed renames without modifying any files.",
        mixinStandardHelpOptions = true
    )
    static class PreviewCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<directory>",
                description = "Root directory to scan.")
        Path root;

        @Option(names = {"--strategy", "-s"},
                description = "Force rename strategy: DATE_LOCATION, MOVIE_TITLE_YEAR, ARTIST_TRACK, DATE_ONLY.")
        String strategy;

        @Option(names = {"--depth", "-d"}, defaultValue = "20",
                description = "Maximum directory depth.")
        int depth;

        @Option(names = "--no-enrich",
                description = "Disable external API enrichment.")
        boolean noEnrich;

        @Option(names = "--plex",
                description = "Use Plex Media Server compatible naming (e.g. 'Movie Title (2014)', 'Show - S01E02 - Title').")
        boolean plexMode;

        @Override
        public Integer call() {
            ScanResult scan = runScan(root, depth, false, false);
            if (scan == null) return 2;

            RenameOptions options = buildRenameOptions(strategy, !noEnrich, true, plexMode);
            SmartRenameEngine engine = new SmartRenameEngine();
            List<RenameProposal> proposals = engine.propose(scan.files(), options);

            int approved = 0, skipped = 0;
            for (RenameProposal p : proposals) {
                switch (p) {
                    case RenameProposal.Approved a -> {
                        ConsoleOutput.success("  " + a.file().filename()
                                + " → " + a.targetPath().getFileName()
                                + "  [" + a.strategyUsed() + "]");
                        approved++;
                    }
                    case RenameProposal.Skipped s ->  {
                        ConsoleOutput.warning("  SKIP " + s.file().filename()
                                + ": " + s.reason());
                        skipped++;
                    }
                }
            }
            ConsoleOutput.info(String.format("%nApproved: %d  Skipped: %d", approved, skipped));
            return skipped > 0 ? 1 : 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Subcommand: rename
    // ═══════════════════════════════════════════════════════════════════════

    @Command(
        name        = "rename",
        description = "Renames media files based on extracted and enriched metadata.",
        mixinStandardHelpOptions = true
    )
    static class RenameCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<directory>",
                description = "Root directory to process.")
        Path root;

        @Option(names = {"--strategy", "-s"},
                description = "Force rename strategy: DATE_LOCATION, MOVIE_TITLE_YEAR, ARTIST_TRACK, DATE_ONLY.")
        String strategy;

        @Option(names = {"--collision", "-c"},
                defaultValue = "SKIP",
                description = "Collision policy: SKIP, SUFFIX, OVERWRITE (default: SKIP).")
        RenameOptions.CollisionPolicy collision;

        @Option(names = {"--depth", "-d"}, defaultValue = "20",
                description = "Maximum directory depth.")
        int depth;

        @Option(names = "--no-enrich",
                description = "Disable external API enrichment.")
        boolean noEnrich;

        @Option(names = "--plex",
                description = "Use Plex Media Server compatible naming (e.g. 'Movie Title (2014)', 'Show - S01E02 - Title').")
        boolean plexMode;

        @Option(names = {"--yes", "-y"},
                description = "Skip confirmation prompt and proceed immediately.")
        boolean skipConfirm;

        @Override
        public Integer call() {
            ScanResult scan = runScan(root, depth, false, false);
            if (scan == null) return 2;

            RenameOptions options = buildRenameOptions(strategy, !noEnrich, false, plexMode)
                    .withCollisionPolicy(collision);
            SmartRenameEngine engine = new SmartRenameEngine();
            List<RenameProposal> proposals = engine.propose(scan.files(), options);

            long approved = proposals.stream()
                    .filter(p -> p instanceof RenameProposal.Approved).count();
            ConsoleOutput.info(approved + " files will be renamed.");

            if (approved == 0) {
                ConsoleOutput.info("Nothing to do.");
                return 0;
            }

            if (!skipConfirm && !ConsoleOutput.confirm("Proceed?")) {
                ConsoleOutput.info("Aborted.");
                return 0;
            }

            UndoJournal journal = new UndoJournal();
            List<RenameResult> results = engine.executeProposals(proposals, journal, false);

            int succeeded = 0, failed = 0, skipped = 0;
            for (RenameResult r : results) {
                switch (r) {
                    case RenameResult.Success s -> {
                        ConsoleOutput.success("✓ " + s.file().filename()
                                + " → " + s.targetPath().getFileName());
                        succeeded++;
                    }
                    case RenameResult.DryRun dr -> succeeded++;
                    case RenameResult.Skipped sk -> {
                        ConsoleOutput.warning("⚠ SKIP " + sk.file().filename() + ": " + sk.reason());
                        skipped++;
                    }
                    case RenameResult.Failed f -> {
                        ConsoleOutput.error("✗ FAIL " + f.file().filename()
                                + ": " + f.cause().getMessage());
                        failed++;
                    }
                }
            }
            ConsoleOutput.info(String.format("%nDone. Renamed: %d  Skipped: %d  Failed: %d",
                    succeeded, skipped, failed));
            return failed > 0 ? 1 : 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Subcommand: undo
    // ═══════════════════════════════════════════════════════════════════════

    @Command(
        name        = "undo",
        description = "Reverts the most recent (or a specific) rename session.",
        mixinStandardHelpOptions = true
    )
    static class UndoCommand implements Callable<Integer> {

        @Option(names = {"--session", "-S"},
                description = "Session UUID to undo (defaults to the last session).")
        String sessionId;

        @Option(names = {"--yes", "-y"},
                description = "Skip confirmation prompt.")
        boolean skipConfirm;

        @Override
        public Integer call() {
            UndoJournal journal = new UndoJournal();

            String targetSession = sessionId;
            if (targetSession == null) {
                Optional<String> last = journal.lastSessionId();
                if (last.isEmpty()) {
                    ConsoleOutput.info("No rename sessions found in the undo journal.");
                    return 0;
                }
                targetSession = last.get();
            }

            List<UndoJournal.Operation> ops = journal.readSession(targetSession);
            if (ops.isEmpty()) {
                ConsoleOutput.info("Session not found: " + targetSession);
                return 1;
            }

            ConsoleOutput.info("Session " + targetSession + " contains "
                    + ops.size() + " operation(s).");

            if (!skipConfirm && !ConsoleOutput.confirm("Undo this session?")) {
                ConsoleOutput.info("Aborted.");
                return 0;
            }

            journal.undoSession(targetSession);
            ConsoleOutput.success("Undo complete.");
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Shared helpers (package-private for tests)
    // ═══════════════════════════════════════════════════════════════════════

    static ScanResult runScan(Path root, int depth, boolean includeHidden, boolean followLinks) {
        MediaScanner scanner = new MediaScanner();
        ScanOptions  options = ScanOptions.defaults()
                .withMaxDepth(depth)
                .withIncludeHidden(includeHidden)
                .withFollowLinks(followLinks);
        try {
            ScanResult result = scanner.scan(root, options);
            log.info("{}", result.summary());
            return result;
        } catch (Exception e) {
            log.error("Scan failed: {}", e.getMessage(), e);
            ConsoleOutput.error("Scan failed: " + e.getMessage());
            return null;
        }
    }

    static RenameOptions buildRenameOptions(String strategyId, boolean enrich, boolean dryRun, boolean plexMode) {
        RenameOptions base = RenameOptions.defaults();
        if (strategyId != null && !strategyId.isBlank()) {
            base = base.withStrategy(strategyId);
        }
        // Rebuild with correct enrich/dryRun/plexMode flags
        return new RenameOptions(base.strategyId(), base.collisionPolicy(), enrich, dryRun, plexMode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Subcommand: duplicates
    // ═══════════════════════════════════════════════════════════════════════

    @Command(
        name        = "duplicates",
        description = "Scans a directory and reports groups of duplicate media files.",
        mixinStandardHelpOptions = true
    )
    static class DuplicatesCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<directory>",
                description = "Root directory to scan.")
        Path root;

        @Option(names = {"--depth", "-d"}, defaultValue = "20",
                description = "Maximum directory depth (default: 20).")
        int depth;

        @Option(names = "--include-hidden",
                description = "Include hidden files and directories.")
        boolean includeHidden;

        @Override
        public Integer call() {
            ScanResult scan = runScan(root, depth, includeHidden, false);
            if (scan == null) return 2;

            if (scan.files().isEmpty()) {
                ConsoleOutput.info("No media files found.");
                return 0;
            }

            DuplicateDetector detector = new DuplicateDetector();
            List<DuplicateGroup> groups = detector.detect(scan.files());

            if (groups.isEmpty()) {
                ConsoleOutput.success("No duplicates found.");
                return 0;
            }

            ConsoleOutput.warning(detector.summarise(groups));
            return 1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Subcommand: cache-clear
    // ═══════════════════════════════════════════════════════════════════════

    @Command(
        name        = "cache-clear",
        description = "Clears all entries from the metadata and API response cache.",
        mixinStandardHelpOptions = true
    )
    static class CacheClearCommand implements Callable<Integer> {

        @Option(names = {"--yes", "-y"},
                description = "Skip confirmation prompt.")
        boolean skipConfirm;

        @Override
        public Integer call() {
            if (!skipConfirm && !ConsoleOutput.confirm("Clear all cache tables? This cannot be undone.")) {
                ConsoleOutput.info("Aborted.");
                return 0;
            }

            try (DatabaseManager db = new DatabaseManager()) {
                MetadataCacheService cache = new MetadataCacheService(db);
                cache.clearAll();
                ConsoleOutput.success("Cache cleared successfully.");
                return 0;
            } catch (Exception e) {
                log.error("Cache clear failed", e);
                ConsoleOutput.error("Cache clear failed: " + e.getMessage());
                return 2;
            }
        }
    }
}
