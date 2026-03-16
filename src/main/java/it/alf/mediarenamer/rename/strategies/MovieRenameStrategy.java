package it.alf.mediarenamer.rename.strategies;

import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.model.MediaType;
import it.alf.mediarenamer.rename.RenameStrategy;

import java.util.Optional;

/**
 * Rename strategy for movie video files.
 *
 * <p>Preferred stem format: {@code Movie_Title_YYYY}
 * Falls back to {@code Movie_Title} when the release year is unknown.</p>
 *
 * <p>Strategy ID: {@value #ID}</p>
 */
public class MovieRenameStrategy implements RenameStrategy {

    public static final String ID = "MOVIE_TITLE_YEAR";

    @Override
    public String id() { return ID; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.VIDEO) return Optional.empty();
        if (metadata.canonicalTitle().isEmpty()) return Optional.empty();

        String title = sanitise(metadata.canonicalTitle().get());
        return Optional.of(metadata.releaseYear()
                .map(year -> title + "_" + year)
                .orElse(title));
    }

    private String sanitise(String title) {
        return title.strip()
                    .replace(' ', '_')
                    .replaceAll("[^\\w_-]", "");
    }
}
