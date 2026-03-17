package it.alf.mediamaster.rename;

import java.util.Optional;

import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaType;
import it.alf.mediamaster.util.FileUtils;

/**
 * {@code MOVIE_TITLE_YEAR} — renames video files as {@code Movie_Title_YYYY}.
 *
 * <p>Example: {@code The_Grand_Budapest_Hotel_2014.mp4}</p>
 */
public class MovieTitleYearStrategy implements RenameStrategy {

    @Override
    public String id() { return "MOVIE_TITLE_YEAR"; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.VIDEO) return Optional.empty();

        Optional<String> title = metadata.canonicalTitle();
        if (title.isEmpty()) return Optional.empty();

        String stem     = FileUtils.sanitiseStem(title.get().replace(" ", "_"));
        String yearPart = metadata.releaseYear().map(y -> "_" + y).orElse("");
        return Optional.of(stem + yearPart);
    }
}
