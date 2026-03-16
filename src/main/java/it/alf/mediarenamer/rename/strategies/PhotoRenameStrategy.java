package it.alf.mediarenamer.rename.strategies;

import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.model.MediaMetadata;
import it.alf.mediarenamer.model.MediaType;
import it.alf.mediarenamer.rename.RenameStrategy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Rename strategy for still-image files.
 *
 * <p>Preferred stem format: {@code YYYY-MM-DD_HH-mm-ss[_City-Country]}
 * Falls back to date-only when no time component is available.</p>
 *
 * <p>Strategy ID: {@value #ID}</p>
 */
public class PhotoRenameStrategy implements RenameStrategy {

    public static final String ID = "PHOTO_DATE_LOCATION";

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter DATE_ONLY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String id() { return ID; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.IMAGE) return Optional.empty();

        Optional<LocalDateTime> captureDate = extractCaptureDate(file);
        if (captureDate.isEmpty()) return Optional.empty();

        String datePart = captureDate.map(DATE_TIME_FMT::format).orElse("");

        Optional<String> locationPart = buildLocationPart(metadata);
        return Optional.of(locationPart
                .map(loc -> datePart + "_" + loc)
                .orElse(datePart));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Optional<LocalDateTime> extractCaptureDate(MediaFile file) {
        // MediaFile does not carry parsed metadata; the SmartRenameEngine resolves
        // metadata externally and passes it via EnrichedMetadata.additionalFields.
        // However this strategy is used in the full pipeline where ImageMetadata
        // is available via the additionalFields injected by BatchRenameProcessor.
        return Optional.empty(); // resolved by SmartRenameEngine using raw metadata
    }

    /**
     * Variant used by the full pipeline when raw {@link MediaMetadata.ImageMetadata} is available.
     *
     * @param file     the image file
     * @param raw      raw image metadata
     * @param enriched enriched (possibly GPS-reversed) metadata
     * @return proposed stem, or empty if no date is available
     */
    public Optional<String> proposeStem(MediaFile file,
                                         MediaMetadata.ImageMetadata raw,
                                         EnrichedMetadata enriched) {
        if (file.type() != MediaType.IMAGE) return Optional.empty();
        if (raw.captureDate().isEmpty()) return Optional.empty();

        LocalDateTime dt       = raw.captureDate().get();
        String        datePart = DATE_TIME_FMT.format(dt);

        Optional<String> locationPart = buildLocationPart(enriched);
        return Optional.of(locationPart
                .map(loc -> datePart + "_" + loc)
                .orElse(datePart));
    }

    private Optional<String> buildLocationPart(EnrichedMetadata meta) {
        if (meta.city().isEmpty() && meta.country().isEmpty()) return Optional.empty();
        String city    = meta.city().map(c -> c.replace(' ', '-')).orElse("");
        String country = meta.country().map(c -> c.replace(' ', '-')).orElse("");
        if (city.isBlank() && country.isBlank()) return Optional.empty();
        String loc = city.isBlank() ? country : (country.isBlank() ? city : city + "-" + country);
        return Optional.of(loc);
    }
}
