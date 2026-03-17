package it.alf.mediamaster.util;

import it.alf.mediamaster.model.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stateless filesystem and filename utilities used across the application.
 *
 * <p>All methods are thread-safe. No instances should be created.</p>
 */
public final class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    /** Characters forbidden in filenames on Windows. */
    private static final String WINDOWS_FORBIDDEN = "\\/:*?\"<>|";

    /** Maximum byte length for a filename component (NTFS / ext4 safe limit). */
    private static final int MAX_FILENAME_BYTES = 240;

    // ── Extension → MediaType mappings ────────────────────────────────────

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "heic", "heif", "tiff", "tif",
            "webp", "gif", "bmp", "raw", "cr2", "nef", "arw", "dng");

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "avi", "mkv", "m4v", "wmv", "flv",
            "webm", "mts", "m2ts", "3gp", "ts");

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "aac", "ogg", "wav", "m4a",
            "wma", "opus", "alac", "aiff", "ape");

    // ── Magic bytes for common formats (first 4–12 bytes) ─────────────────

    /** Maps a format name to the leading magic-byte sequence (hex string). */
    private static final Map<String, String> MAGIC_BYTES = Map.of(
            "jpeg", "ffd8ff",
            "png",  "89504e47",
            "gif",  "47494638",
            "webp", "52494646",   // RIFF header — check bytes 8-11 for "WEBP"
            "mp4",  "00000018667479"  // ftyp box (offset 4); simplified check
    );

    private FileUtils() {
        // Utility class — no instances
    }

    // ── Media type detection ───────────────────────────────────────────────

    /**
     * Detects the {@link MediaType} of a file by its extension (case-insensitive).
     *
     * @param path path to the file
     * @return the detected type, or {@link Optional#empty()} if not a recognised media file
     */
    public static Optional<MediaType> detectMediaType(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        String ext = extension(path);
        if (IMAGE_EXTENSIONS.contains(ext)) return Optional.of(MediaType.IMAGE);
        if (VIDEO_EXTENSIONS.contains(ext)) return Optional.of(MediaType.VIDEO);
        if (AUDIO_EXTENSIONS.contains(ext)) return Optional.of(MediaType.AUDIO);
        return Optional.empty();
    }

    /**
     * Returns the lowercase extension of the given path, without the leading dot.
     *
     * @param path file path
     * @return lowercase extension, or {@code ""} if none
     */
    public static String extension(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Returns the stem (name without extension) of the last path component.
     *
     * @param path file path
     * @return filename stem
     */
    public static String stem(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    // ── Filename sanitisation ──────────────────────────────────────────────

    /**
     * Sanitises a proposed filename stem so it is safe on Windows, macOS and Linux.
     *
     * <p>Rules applied:</p>
     * <ol>
     *   <li>Replace Windows-forbidden characters ({@code \/:*?"<>|}) with {@code _}</li>
     *   <li>Replace NUL bytes with {@code _}</li>
     *   <li>Collapse consecutive underscores and spaces</li>
     *   <li>Trim leading/trailing dots and whitespace</li>
     *   <li>Truncate to {@value #MAX_FILENAME_BYTES} bytes (UTF-8) if necessary</li>
     *   <li>Fall back to {@code "unnamed_<hashCode>"} if the result is empty</li>
     * </ol>
     *
     * @param raw proposed stem (without extension)
     * @return sanitised stem, never {@code null}, never empty
     */
    public static String sanitiseStem(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");

        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (WINDOWS_FORBIDDEN.indexOf(c) >= 0 || c == '\0') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }

        // Collapse consecutive underscores / spaces
        String result = sb.toString()
                .replaceAll("[_ ]{2,}", "_")
                .strip()
                .replaceAll("^[.]+", "")   // no leading dots
                .replaceAll("[.]+$", "");   // no trailing dots

        // Byte-length truncation
        while (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES
                && !result.isEmpty()) {
            result = result.substring(0, result.length() - 1);
        }

        if (result.isEmpty()) {
            result = "unnamed_" + Math.abs(raw.hashCode());
        }

        return result;
    }

    // ── Collision-free path resolution ────────────────────────────────────

    /**
     * Resolves a collision-free target path inside {@code targetDir}.
     *
     * <p>If {@code targetDir/stem.ext} does not exist it is returned as-is.
     * Otherwise {@code stem_1.ext}, {@code stem_2.ext}, … are tried until
     * a free slot is found (up to 999 attempts).</p>
     *
     * @param targetDir directory where the file should land
     * @param stem      desired filename stem (pre-sanitised)
     * @param extension lowercase extension without leading dot
     * @return a path that does not currently exist in the filesystem
     * @throws IllegalStateException if no free slot is found after 999 attempts
     */
    public static Path resolveCollisionFreePath(Path targetDir, String stem, String extension) {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        Objects.requireNonNull(stem, "stem must not be null");
        Objects.requireNonNull(extension, "extension must not be null");

        String filename = extension.isEmpty() ? stem : stem + "." + extension;
        Path candidate = targetDir.resolve(filename);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        for (int i = 1; i < 1000; i++) {
            String suffixed = extension.isEmpty()
                    ? stem + "_" + i
                    : stem + "_" + i + "." + extension;
            candidate = targetDir.resolve(suffixed);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not find a collision-free path for stem='" + stem + "' in " + targetDir);
    }

    // ── Content hashing ───────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex digest of the file at the given path.
     *
     * <p>Used by the duplicate detector to compare file contents.</p>
     *
     * @param path path to the file to hash
     * @return lowercase hex SHA-256 digest string
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — this cannot happen
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }

    // ── Path safety ───────────────────────────────────────────────────────

    /**
     * Validates that {@code child} is located inside {@code root} after normalisation.
     *
     * <p>This prevents path-traversal attacks when user-supplied paths are used
     * to construct target filenames.</p>
     *
     * @param root  the trusted root directory
     * @param child the path to validate
     * @return {@code child} normalised, guaranteed to be under {@code root}
     * @throws SecurityException if {@code child} escapes {@code root}
     */
    public static Path requireUnderRoot(Path root, Path child) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(child, "child must not be null");

        Path normRoot  = root.toAbsolutePath().normalize();
        Path normChild = child.toAbsolutePath().normalize();

        if (!normChild.startsWith(normRoot)) {
            throw new SecurityException(
                    "Path traversal detected: '" + normChild + "' is outside root '" + normRoot + "'");
        }
        return normChild;
    }

    /**
     * Checks whether the given path string is syntactically valid for the current filesystem.
     *
     * @param pathString the path string to validate
     * @return {@code true} if the path string can be parsed by the default filesystem
     */
    public static boolean isValidPath(String pathString) {
        if (pathString == null || pathString.isBlank()) return false;
        try {
            Path.of(pathString);
            return true;
        } catch (InvalidPathException e) {
            log.debug("Invalid path string: '{}' — {}", pathString, e.getMessage());
            return false;
        }
    }
}
