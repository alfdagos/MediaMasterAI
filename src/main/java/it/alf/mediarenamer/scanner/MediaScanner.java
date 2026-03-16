package it.alf.mediarenamer.scanner;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.util.FileUtils;

/**
 * Recursively scans a root directory for media files (images, videos, audio).
 *
 * <p>Scanning strategy:</p>
 * <ol>
 *   <li>Walk the directory tree using {@link Files#walkFileTree}</li>
 *   <li>Skip hidden directories unless {@link ScanOptions#includeHidden()} is {@code true}</li>
 *   <li>Skip directories matching any pattern in {@link ScanOptions#excludeGlobs()}</li>
 *   <li>Detect media type by file extension via {@link FileUtils#detectMediaType}</li>
 *   <li>Guard against path traversal — all returned paths are under {@code root}</li>
 * </ol>
 *
 * <p>This class is thread-safe and stateless; a single instance can be reused across scans.</p>
 */
public class MediaScanner {

    private static final Logger log = LoggerFactory.getLogger(MediaScanner.class);

    /** Directories always skipped regardless of options. */
    private static final Set<String> ALWAYS_SKIP = Set.of(
            ".git", ".svn", ".hg", ".Trashes", ".Spotlight-V100", "node_modules", "__MACOSX");

    /**
     * Scans the given root directory using default options.
     *
     * @param root the directory to scan; must exist and be accessible
     * @return populated {@link ScanResult}
     */
    public ScanResult scan(Path root) {
        return scan(root, ScanOptions.defaults());
    }

    /**
     * Scans the given root directory using the supplied options.
     *
     * @param root    the directory to scan; must exist and be accessible
     * @param options tuning parameters for the scan
     * @return populated {@link ScanResult}
     * @throws IllegalArgumentException if {@code root} does not exist or is not a directory
     */
    public ScanResult scan(Path root, ScanOptions options) {
        Objects.requireNonNull(root,    "root must not be null");
        Objects.requireNonNull(options, "options must not be null");

        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Root is not an existing directory: " + root);
        }

        log.info("Starting scan of: {} (maxDepth={}, parallel={})",
                root, options.maxDepth(), options.parallel());

        Instant start = Instant.now();
        List<MediaFile>    found  = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger      total  = new AtomicInteger(0);
        AtomicInteger      errors = new AtomicInteger(0);
        List<PathMatcher>  excludeMatchers = compileGlobs(options.excludeGlobs());

        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Always skip known noise directories
                String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (ALWAYS_SKIP.contains(dirName)) {
                    log.debug("Skipping noise directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Skip hidden directories (starting with '.') unless included
                if (!options.includeHidden() && dirName.startsWith(".") && !dir.equals(root)) {
                    log.debug("Skipping hidden directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Skip excluded globs
                for (PathMatcher m : excludeMatchers) {
                    if (m.matches(dir.getFileName())) {
                        log.debug("Skipping excluded directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total.incrementAndGet();

                if (total.get() % 1_000 == 0) {
                    log.debug("Scan progress: {} files visited so far in {}", total.get(), root);
                }

                // Skip hidden files unless requested
                String name = file.getFileName().toString();
                if (!options.includeHidden() && name.startsWith(".")) {
                    return FileVisitResult.CONTINUE;
                }
                // Skip excluded globs
                for (PathMatcher m : excludeMatchers) {
                    if (m.matches(file.getFileName())) return FileVisitResult.CONTINUE;
                }

                // Enforce that the file is under the root (guard against symlink escapes)
                try {
                    FileUtils.requireUnderRoot(root, file);
                } catch (SecurityException e) {
                    log.warn("Path traversal prevented: {}", e.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                FileUtils.detectMediaType(file).ifPresent(type -> {
                    found.add(new MediaFile(file.toAbsolutePath().normalize(), type,
                            attrs.size(), attrs.lastModifiedTime()));
                });
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                errors.incrementAndGet();
                log.warn("Cannot visit file: {} — {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        };

        EnumSet<FileVisitOption> walkOptions = options.followLinks()
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);

        try {
            Files.walkFileTree(root, walkOptions, options.maxDepth(), visitor);
        } catch (IOException e) {
            log.error("Fatal error during scan of {}: {}", root, e.getMessage(), e);
        }

        // Sort for deterministic ordering
        List<MediaFile> sortedFound = found.stream()
                .sorted(Comparator.comparing(f -> f.path().toString()))
                .collect(Collectors.toUnmodifiableList());

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("Scan complete: {} media files found, {} total visited, {} errors, elapsed={}ms",
                sortedFound.size(), total.get(), errors.get(), elapsed.toMillis());

        return new ScanResult(sortedFound, total.get(), errors.get(), elapsed);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private List<PathMatcher> compileGlobs(List<String> globs) {
        FileSystem fs = FileSystems.getDefault();
        return globs.stream()
                .map(g -> fs.getPathMatcher("glob:" + g))
                .collect(Collectors.toUnmodifiableList());
    }
}
