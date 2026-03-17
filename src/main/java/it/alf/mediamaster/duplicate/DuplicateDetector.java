package it.alf.mediamaster.duplicate;

import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects duplicate media files within a collection.
 *
 * <p>Duplicates are identified by comparing SHA-256 content hashes. Files
 * with identical hashes are guaranteed to have identical content regardless
 * of their filename or location.</p>
 *
 * <p>Optionally a fast pre-filter by file size eliminates obviously distinct
 * files before the hash comparison, which can significantly reduce I/O for
 * large collections.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * DuplicateDetector detector = new DuplicateDetector();
 * List<DuplicateGroup> groups = detector.detect(mediaFiles);
 * groups.forEach(g -> {
 *     System.out.println("Duplicate group (" + g.fileCount() + " files):");
 *     g.files().forEach(f -> System.out.println("  " + f.path()));
 * });
 * }</pre>
 */
public class DuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetector.class);

    /**
     * Detects all groups of duplicate files in the given list.
     *
     * <p>Files for which hashing fails (e.g. permission denied) are logged
     * at WARN level and silently excluded from results.</p>
     *
     * @param files the collection to inspect; must not be {@code null}
     * @return an unmodifiable list of {@link DuplicateGroup} objects, each containing
     *         at least two files; empty if no duplicates are found
     */
    public List<DuplicateGroup> detect(List<MediaFile> files) {
        Objects.requireNonNull(files, "files must not be null");
        log.info("Scanning {} files for duplicates", files.size());

        // Fast pre-filter: group by file size to skip clearly distinct files
        Map<Long, List<MediaFile>> bySize = files.stream()
                .collect(Collectors.groupingBy(MediaFile::sizeBytes));

        Map<String, List<MediaFile>> byHash = new LinkedHashMap<>();

        for (Map.Entry<Long, List<MediaFile>> sizeGroup : bySize.entrySet()) {
            if (sizeGroup.getValue().size() < 2) continue; // unique size — skip hashing

            for (MediaFile file : sizeGroup.getValue()) {
                try {
                    String hash = FileUtils.sha256(file.path());
                    byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(file);
                } catch (IOException e) {
                    log.warn("Cannot hash file {} ({}); skipped", file.path(), e.getMessage());
                }
            }
        }

        List<DuplicateGroup> result = byHash.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .map(e -> new DuplicateGroup(e.getKey(),
                        e.getValue().getFirst().sizeBytes(),
                        Collections.unmodifiableList(e.getValue())))
                .toList();

        log.info("Found {} duplicate group(s) from {} files", result.size(), files.size());
        return result;
    }

    /**
     * Returns a summary string suitable for CLI output.
     *
     * @param groups the result from {@link #detect}
     * @return formatted multi-line summary
     */
    public String summarise(List<DuplicateGroup> groups) {
        if (groups.isEmpty()) return "No duplicates found.";

        long wastedBytes = groups.stream()
                .mapToLong(g -> g.fileSize() * (g.fileCount() - 1L))
                .sum();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d duplicate group(s) — %s wasted%n",
                groups.size(), formatBytes(wastedBytes)));
        for (int i = 0; i < groups.size(); i++) {
            DuplicateGroup g = groups.get(i);
            sb.append(String.format("  Group %d  [%d files, %s each, hash: %.8s…]%n",
                    i + 1, g.fileCount(), formatBytes(g.fileSize()), g.hash()));
            g.files().forEach(f -> sb.append("    ").append(f.path()).append(System.lineSeparator()));
        }
        return sb.toString().strip();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ── DuplicateGroup ─────────────────────────────────────────────────────

    /**
     * An immutable group of two or more files with identical content.
     *
     * @param hash     SHA-256 hex digest shared by all files in this group
     * @param fileSize byte size of each file
     * @param files    the files in the group (at least 2)
     */
    public record DuplicateGroup(String hash, long fileSize, List<MediaFile> files) {

        public DuplicateGroup {
            Objects.requireNonNull(hash,  "hash must not be null");
            Objects.requireNonNull(files, "files must not be null");
            if (files.size() < 2) throw new IllegalArgumentException("DuplicateGroup requires >= 2 files");
        }

        /** Returns the number of files in this group. */
        public int fileCount() { return files.size(); }

        /** Returns the "oldest" file (earliest last-modified), which is typically considered the original. */
        public Optional<MediaFile> original() {
            return files.stream().min(Comparator.comparing(f -> f.lastModified().toInstant()));
        }

        /** Returns all files except the one identified as {@link #original()}. */
        public List<MediaFile> duplicateFiles() {
            return original()
                    .map(orig -> files.stream().filter(f -> !f.path().equals(orig.path())).toList())
                    .orElse(List.of());
        }
    }
}
