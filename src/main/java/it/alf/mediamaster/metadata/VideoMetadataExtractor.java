package it.alf.mediamaster.metadata;

import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts {@link MediaMetadata.VideoMetadata} from video container files by reading
 * a small portion of the file header without parsing the full container.
 *
 * <p>Supported containers and strategies:</p>
 * <ul>
 *   <li><b>MP4 / MOV</b> — scans for {@code ©day} / {@code date} atom inside the first
 *       {@code moov} atoms (up to the first 256 KB of the file).</li>
 *   <li><b>MKV / WebM</b> — reads the EBML header and the {@code \x44\x61} (DateUTC) element
 *       from the {@code Segment Info} block (up to the first 128 KB).</li>
 *   <li>Other formats — returns empty optionals rather than throwing.</li>
 * </ul>
 *
 * <p>When {@code ffprobe} is on the system {@code PATH} and the in-process extraction
 * yields no date, the extractor will fall back to a subprocess call and parse its JSON
 * output. The ffprobe fallback can be disabled via {@link #setFfprobeFallbackEnabled}.</p>
 */
public class VideoMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(VideoMetadataExtractor.class);

    /** Maximum bytes read from the file for in-process container inspection. */
    private static final int MAX_HEADER_BYTES = 256 * 1024; // 256 KB

    private static final byte[] MP4_FTYP = "ftyp".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MP4_DAY  = "\u00a9day".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MKV_EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    private boolean ffprobeFallbackEnabled = true;

    /**
     * Enables or disables the {@code ffprobe} fallback subprocess call.
     *
     * @param enabled {@code true} (default) to allow subprocess fallback
     */
    public void setFfprobeFallbackEnabled(boolean enabled) {
        this.ffprobeFallbackEnabled = enabled;
    }

    /**
     * Extracts video metadata from the given {@link MediaFile}.
     *
     * @param mediaFile the video file to inspect; must not be {@code null}
     * @return populated {@link MediaMetadata.VideoMetadata} record (fields may be empty)
     * @throws MetadataExtractionException if the file cannot be opened
     */
    public MediaMetadata.VideoMetadata extract(MediaFile mediaFile) throws MetadataExtractionException {
        Objects.requireNonNull(mediaFile, "mediaFile must not be null");

        Path path = mediaFile.path();
        log.debug("Extracting video metadata from: {}", path);

        byte[] header = readHeader(path);
        Optional<LocalDateTime> creationDate = Optional.empty();
        Optional<String>        title        = Optional.empty();

        if (isMp4OrMov(header)) {
            creationDate = extractMp4Date(header, path);
            title        = extractMp4Title(header, path);
        } else if (isMkv(header)) {
            creationDate = extractMkvDate(header, path);
        }

        // ffprobe fallback when in-process extraction could not find a date
        if (creationDate.isEmpty() && ffprobeFallbackEnabled) {
            creationDate = extractDateViaFfprobe(path);
        }

        return new MediaMetadata.VideoMetadata(creationDate, title, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    // ── Container detection ────────────────────────────────────────────────

    private boolean isMp4OrMov(byte[] header) {
        // MP4/MOV containers contain "ftyp" at byte offset 4, or "moov"/"mdat" further in
        return containsSequence(header, MP4_FTYP) || containsAtomName(header, "moov");
    }

    private boolean isMkv(byte[] header) {
        if (header.length < 4) return false;
        return (header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45
                && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3;
    }

    // ── MP4 / MOV extraction ───────────────────────────────────────────────

    private Optional<LocalDateTime> extractMp4Date(byte[] header, Path path) {
        // Look for ©day atom which holds an ISO-8601 date string
        int idx = indexOf(header, MP4_DAY, 0);
        if (idx < 0) {
            log.debug("No ©day atom found in {}", path.getFileName());
            return Optional.empty();
        }
        // The atom has a 4-byte size + 4-byte name, then a 4-byte data header, then the string
        int dataStart = idx + 4 + 8; // past atom name + data header (simplified)
        if (dataStart >= header.length) return Optional.empty();

        String raw = readNullTerminatedString(header, dataStart, 64);
        return parseIso8601Date(raw, path);
    }

    private Optional<String> extractMp4Title(byte[] header, Path path) {
        byte[] namAtom = "\u00a9nam".getBytes(StandardCharsets.ISO_8859_1);
        int idx = indexOf(header, namAtom, 0);
        if (idx < 0) return Optional.empty();
        int dataStart = idx + 4 + 8;
        if (dataStart >= header.length) return Optional.empty();
        String title = readNullTerminatedString(header, dataStart, 256);
        return title.isBlank() ? Optional.empty() : Optional.of(title.strip());
    }

    // ── MKV extraction ─────────────────────────────────────────────────────

    private Optional<LocalDateTime> extractMkvDate(byte[] header, Path path) {
        // MKV DateUTC element ID = 0x4461; value is nanoseconds since 2001-01-01T00:00:00Z
        byte[] dateId = {0x44, 0x61};
        int idx = indexOf(header, dateId, 0);
        if (idx < 0) {
            log.debug("No DateUTC element found in MKV file: {}", path.getFileName());
            return Optional.empty();
        }
        // Next byte is the VINT-encoded size; for a date it should be 8 bytes
        idx += 2;
        if (idx + 1 >= header.length) return Optional.empty();
        int size = header[idx] & 0x7F; // simplified VINT for size <= 127
        idx++;
        if (size != 8 || idx + 8 > header.length) return Optional.empty();

        long nanosSince2001 = ByteBuffer.wrap(header, idx, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();
        // MKV epoch is 2001-01-01T00:00:00Z
        long secondsSince2001 = nanosSince2001 / 1_000_000_000L;
        LocalDateTime mkvEpoch = LocalDateTime.of(2001, 1, 1, 0, 0, 0);
        return Optional.of(mkvEpoch.plusSeconds(secondsSince2001));
    }

    // ── ffprobe fallback ───────────────────────────────────────────────────

    private Optional<LocalDateTime> extractDateViaFfprobe(Path path) {
        log.debug("Trying ffprobe fallback for: {}", path.getFileName());
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_entries", "format_tags=creation_time",
                    path.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();

            // Parse "creation_time" from JSON output
            int keyIdx = output.indexOf("\"creation_time\"");
            if (keyIdx < 0) return Optional.empty();
            int colonIdx = output.indexOf(':', keyIdx);
            int startQuote = output.indexOf('"', colonIdx + 1);
            int endQuote   = output.indexOf('"', startQuote + 1);
            if (startQuote < 0 || endQuote < 0) return Optional.empty();
            String raw = output.substring(startQuote + 1, endQuote);
            return parseIso8601Date(raw, path);
        } catch (IOException | InterruptedException e) {
            log.debug("ffprobe not available or failed for {}: {}", path.getFileName(), e.getMessage());
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // ── Byte-level utilities ───────────────────────────────────────────────

    private byte[] readHeader(Path path) throws MetadataExtractionException {
        try (InputStream is = Files.newInputStream(path)) {
            return is.readNBytes(MAX_HEADER_BYTES);
        } catch (IOException e) {
            throw new MetadataExtractionException("Cannot open video file: " + path, e);
        }
    }

    private boolean containsSequence(byte[] data, byte[] sequence) {
        return indexOf(data, sequence, 0) >= 0;
    }

    private boolean containsAtomName(byte[] data, String name) {
        return containsSequence(data, name.getBytes(StandardCharsets.ISO_8859_1));
    }

    private int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private String readNullTerminatedString(byte[] data, int start, int maxLen) {
        int end = start;
        while (end < data.length && end < start + maxLen && data[end] != 0) end++;
        return new String(data, start, end - start, StandardCharsets.UTF_8)
                .replaceAll("[\\x00-\\x1F]", "").strip();
    }

    private Optional<LocalDateTime> parseIso8601Date(String raw, Path path) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        // Try full ISO-8601 with time first, then date-only
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd")}) {
            try {
                return Optional.of(LocalDateTime.parse(raw.trim(), fmt));
            } catch (DateTimeParseException ignored) {}
        }
        log.debug("Could not parse date string '{}' in {}", raw, path.getFileName());
        return Optional.empty();
    }
}
