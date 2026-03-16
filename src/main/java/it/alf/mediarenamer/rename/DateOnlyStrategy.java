package it.alf.mediarenamer.rename;

import java.time.LocalDateTime;
import java.util.Optional;

import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaFile;

/**
 * {@code DATE_ONLY} — fallback strategy for any media type that has a date field.
 * Produces {@code YYYY-MM-DD_HH-mm-ss}.
 *
 * <p>Example: {@code 2024-03-15_14-32-07.jpg}</p>
 */
public class DateOnlyStrategy implements RenameStrategy {

    @Override
    public String id() { return "DATE_ONLY"; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        return Optional.empty(); // resolved by engine using raw metadata
    }

    /**
     * Generates a date-only stem from an arbitrary {@link LocalDateTime}.
     *
     * @param date optional date; when empty the method returns {@link Optional#empty()}
     * @return formatted stem {@code YYYY-MM-DD_HH-mm-ss}, or empty
     */
    public Optional<String> proposeStem(Optional<LocalDateTime> date) {
        return date.map(d -> d.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
    }
}
