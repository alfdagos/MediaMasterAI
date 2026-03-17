package it.alf.mediamaster.scanner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import it.alf.mediamaster.model.MediaFile;
import static it.alf.mediamaster.model.MediaType.AUDIO;
import static it.alf.mediamaster.model.MediaType.IMAGE;
import static it.alf.mediamaster.model.MediaType.VIDEO;

/**
 * Immutable summary of a completed directory scan.
 *
 * @param files             unmodifiable, sorted list of media files found
 * @param totalVisited      total number of filesystem entries (files and directories) visited
 * @param errorsEncountered number of files that could not be visited due to I/O errors
 * @param elapsed           wall-clock duration of the scan
 */
public record ScanResult(
        List<MediaFile> files,
        int totalVisited,
        int errorsEncountered,
        Duration elapsed) {

    public ScanResult {
        Objects.requireNonNull(files,   "files must not be null");
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        files = List.copyOf(files);
    }

    /** Returns the number of image files found. */
    public long imageCount() {
        return files.stream().filter(f -> f.type() == IMAGE).count();
    }

    /** Returns the number of video files found. */
    public long videoCount() {
        return files.stream().filter(f -> f.type() == VIDEO).count();
    }

    /** Returns the number of audio files found. */
    public long audioCount() {
        return files.stream().filter(f -> f.type() == AUDIO).count();
    }

    /** Returns the total size of all media files in bytes. */
    public long totalSizeBytes() {
        return files.stream().mapToLong(MediaFile::sizeBytes).sum();
    }

    /**
     * Returns a human-readable summary string suitable for console output.
     *
     * @return multi-line summary
     */
    public String summary() {
        return String.format("""
                Scan complete in %d ms
                  Total visited : %,d
                  Media found   : %,d
                    Images  : %,d
                    Videos  : %,d
                    Audio   : %,d
                  Errors    : %,d
                """,
                elapsed.toMillis(),
                totalVisited,
                files.size(),
                imageCount(),
                videoCount(),
                audioCount(),
                errorsEncountered);
    }
}
