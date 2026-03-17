package it.alf.mediamaster.scanner;

import java.util.List;
import java.util.Objects;

/**
 * Immutable options record for controlling a {@link MediaScanner} run.
 *
 * @param maxDepth      maximum directory depth to traverse
 *                      ({@link Integer#MAX_VALUE} = unlimited)
 * @param followLinks   whether to follow symbolic links during traversal
 * @param includeHidden whether to include hidden files and directories
 * @param parallel      reserved for future parallel traversal support
 * @param excludeGlobs  list of glob patterns; files/directories matching any
 *                      pattern are skipped (e.g. {@code "*.tmp"}, {@code "Thumbs*"})
 */
public record ScanOptions(
        int maxDepth,
        boolean followLinks,
        boolean includeHidden,
        boolean parallel,
        List<String> excludeGlobs) {

    /** Compact canonical constructor. */
    public ScanOptions {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got: " + maxDepth);
        }
        excludeGlobs = List.copyOf(Objects.requireNonNull(excludeGlobs, "excludeGlobs must not be null"));
    }

    /**
     * Returns default scan options: unlimited depth, no symlink following,
     * hidden files excluded, no glob exclusions.
     */
    public static ScanOptions defaults() {
        return new ScanOptions(Integer.MAX_VALUE, false, false, false, List.of());
    }

    /**
     * Returns a copy of these options with the given maximum depth.
     */
    public ScanOptions withMaxDepth(int depth) {
        return new ScanOptions(depth, followLinks, includeHidden, parallel, excludeGlobs);
    }

    /** Returns a copy of these options with the given {@code includeHidden} flag. */
    public ScanOptions withIncludeHidden(boolean include) {
        return new ScanOptions(maxDepth, followLinks, include, parallel, excludeGlobs);
    }

    /** Returns a copy of these options with the given {@code followLinks} flag. */
    public ScanOptions withFollowLinks(boolean follow) {
        return new ScanOptions(maxDepth, follow, includeHidden, parallel, excludeGlobs);
    }
}
