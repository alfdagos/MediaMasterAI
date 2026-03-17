package it.alf.mediamaster.model;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Immutable representation of a media file discovered during a directory scan.
 *
 * <p>This record is the central domain object passed through every processing stage:
 * scanning → metadata extraction → enrichment → rename engine.</p>
 *
 * @param path         absolute path to the file on the filesystem
 * @param type         the detected media type (IMAGE, VIDEO or AUDIO)
 * @param sizeBytes    file size in bytes as reported by the filesystem
 * @param lastModified last-modified timestamp as reported by the filesystem
 */
public record MediaFile(
        Path path,
        MediaType type,
        long sizeBytes,
        FileTime lastModified) {

    /**
     * Compact canonical constructor that validates required fields.
     */
    public MediaFile {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(lastModified, "lastModified must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0, got: " + sizeBytes);
        }
    }

    /**
     * Returns the filename component of the path (without parent directories).
     *
     * @return filename as a {@code String}
     */
    public String filename() {
        return path.getFileName().toString();
    }

    /**
     * Returns the file extension in lowercase, without the leading dot.
     * Returns an empty string if the filename has no extension.
     *
     * <p>Example: {@code "IMG_0042.JPG"} → {@code "jpg"}</p>
     *
     * @return lowercase extension, or {@code ""} if none
     */
    public String extension() {
        String name = filename();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Returns the filename stem — the part before the last dot.
     *
     * <p>Example: {@code "IMG_0042.jpg"} → {@code "IMG_0042"}</p>
     *
     * @return filename stem
     */
    public String stem() {
        String name = filename();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
