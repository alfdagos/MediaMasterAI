package it.alf.mediamaster.metadata;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts {@link MediaMetadata.ImageMetadata} from still-image files using the
 * <a href="https://github.com/drewnoakes/metadata-extractor">metadata-extractor</a> library.
 *
 * <p>Supported formats include JPEG, PNG, TIFF, WebP, GIF, BMP and all major RAW
 * formats (CR2, NEF, ARW, DNG).</p>
 *
 * <p>This class is designed to be instantiated once and reused across many files.</p>
 */
public class ImageMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageMetadataExtractor.class);

    /**
     * Extracts image metadata from the given {@link MediaFile}.
     *
     * <p>If a specific tag cannot be read, the corresponding {@code Optional} field
     * will be {@link Optional#empty()} — extraction is never aborted because of a
     * single missing tag.</p>
     *
     * @param mediaFile the image file to inspect; must not be {@code null}
     * @return populated {@link MediaMetadata.ImageMetadata} record
     * @throws MetadataExtractionException if the file cannot be opened or is not a
     *                                     recognised image format
     */
    public MediaMetadata.ImageMetadata extract(MediaFile mediaFile) throws MetadataExtractionException {
        Objects.requireNonNull(mediaFile, "mediaFile must not be null");

        Path path = mediaFile.path();
        log.debug("Extracting image metadata from: {}", path);

        Metadata drewMetadata = readMetadata(path);

        Optional<LocalDateTime> captureDate = extractCaptureDate(drewMetadata, path);
        Optional<Double>        latitude    = Optional.empty();
        Optional<Double>        longitude   = Optional.empty();
        Optional<String>        make        = Optional.empty();
        Optional<String>        model       = Optional.empty();
        Optional<Integer>       width       = Optional.empty();
        Optional<Integer>       height      = Optional.empty();

        // ── GPS ─────────────────────────────────────────────────────────────
        GpsDirectory gpsDir = drewMetadata.getFirstDirectoryOfType(GpsDirectory.class);
        if (gpsDir != null) {
            GeoLocation loc = gpsDir.getGeoLocation();
            if (loc != null && !loc.isZero()) {
                latitude  = Optional.of(loc.getLatitude());
                longitude = Optional.of(loc.getLongitude());
                log.debug("GPS found in {}: lat={}, lon={}", path.getFileName(),
                        loc.getLatitude(), loc.getLongitude());
            }
        }

        // ── Camera make / model ──────────────────────────────────────────────
        ExifIFD0Directory ifd0 = drewMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null) {
            make  = Optional.ofNullable(ifd0.getString(ExifIFD0Directory.TAG_MAKE)).map(String::strip);
            model = Optional.ofNullable(ifd0.getString(ExifIFD0Directory.TAG_MODEL)).map(String::strip);
        }

        // ── Dimensions ──────────────────────────────────────────────────────
        JpegDirectory jpegDir = drewMetadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpegDir != null) {
            try { width  = Optional.of(jpegDir.getImageWidth()); }  catch (Exception ignored) {}
            try { height = Optional.of(jpegDir.getImageHeight()); } catch (Exception ignored) {}
        }
        // For non-JPEG formats the dimensions may appear in EXIF
        if (width.isEmpty() && ifd0 != null) {
            width  = tryGetInteger(ifd0, ExifIFD0Directory.TAG_IMAGE_WIDTH);
            height = tryGetInteger(ifd0, ExifIFD0Directory.TAG_IMAGE_HEIGHT);
        }

        log.debug("Finished extracting metadata from {}: captureDate={}, hasGps={}",
                path.getFileName(), captureDate.isPresent(), latitude.isPresent());

        return new MediaMetadata.ImageMetadata(captureDate, latitude, longitude, make, model, width, height);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private Metadata readMetadata(Path path) throws MetadataExtractionException {
        try {
            return ImageMetadataReader.readMetadata(path.toFile());
        } catch (ImageProcessingException | IOException e) {
            throw new MetadataExtractionException(
                    "Cannot read image metadata from: " + path, e);
        }
    }

    private Optional<LocalDateTime> extractCaptureDate(Metadata drewMetadata, Path path) {
        // Prefer DateTimeOriginal (tag 0x9003) from ExifSubIFD
        ExifSubIFDDirectory subIfd = drewMetadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (subIfd != null) {
            Date date = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (date != null) {
                return Optional.of(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            }
        }
        // Fallback to IFD0 DateTime
        ExifIFD0Directory ifd0 = drewMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null) {
            Date date = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME);
            if (date != null) {
                log.debug("Using IFD0 DateTime (fallback) for {}", path.getFileName());
                return Optional.of(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            }
        }
        log.debug("No capture date found in EXIF for {}", path.getFileName());
        return Optional.empty();
    }

    private Optional<Integer> tryGetInteger(ExifIFD0Directory dir, int tag) {
        try {
            return dir.containsTag(tag) ? Optional.of(dir.getInt(tag)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
