package it.alf.mediarenamer.rename;

import java.time.LocalDateTime;
import java.util.Optional;

import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.model.MediaMetadata;
import it.alf.mediarenamer.model.MediaType;
import it.alf.mediarenamer.util.FileUtils;

/**
 * {@code DATE_LOCATION} — renames images as {@code YYYY-MM-DD_HH-mm-ss[_City-Country]}.
 *
 * <p>Requires: IMAGE type with a capture date extracted from EXIF.
 * City and country from geo-enrichment are appended when available.</p>
 *
 * <p>Example: {@code 2024-03-15_14-32-07_Rome-Italy.jpg}</p>
 */
public class DateLocationStrategy implements RenameStrategy {

    @Override
    public String id() { return "DATE_LOCATION"; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.IMAGE) return Optional.empty();
        return Optional.empty(); // resolved by engine with raw metadata
    }

    /**
     * Full proposal using both raw and enriched metadata.
     *
     * @param file      the image file
     * @param imageMeta raw image metadata
     * @param enriched  enriched metadata (provides city/country)
     * @return proposed stem, or empty if capture date is absent
     */
    public Optional<String> proposeStem(MediaFile file,
                                        MediaMetadata.ImageMetadata imageMeta,
                                        EnrichedMetadata enriched) {
        if (file.type() != MediaType.IMAGE) return Optional.empty();

        Optional<LocalDateTime> date = imageMeta.captureDate();
        if (date.isEmpty()) return Optional.empty();

        String datePart = date.get().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

        String locationPart = "";
        if (enriched.city().isPresent() || enriched.country().isPresent()) {
            String city    = enriched.city().orElse("");
            String country = enriched.country().orElse("");
            String raw     = city.isBlank() ? country
                    : (country.isBlank() ? city : city + "-" + country);
            if (!raw.isBlank()) {
                locationPart = "_" + FileUtils.sanitiseStem(raw.replace(" ", "-"));
            }
        }
        return Optional.of(datePart + locationPart);
    }
}
