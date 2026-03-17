package it.alf.mediamaster.rename;

import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaType;
import it.alf.mediamaster.util.FileUtils;

import java.util.Optional;

/**
 * {@code ARTIST_TRACK} — renames audio files as {@code Artist_-_Track_Title}.
 *
 * <p>Example: {@code Pink_Floyd_-_Comfortably_Numb.mp3}</p>
 */
public class ArtistTrackStrategy implements RenameStrategy {

    @Override
    public String id() { return "ARTIST_TRACK"; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.AUDIO) return Optional.empty();

        Optional<String> artist = metadata.artistName();
        Optional<String> title  = metadata.canonicalTitle();

        if (artist.isEmpty() && title.isEmpty()) return Optional.empty();

        String artistPart = artist.map(a -> FileUtils.sanitiseStem(a.replace(" ", "_")))
                                   .orElse("Unknown_Artist");
        String titlePart  = title.map(t -> FileUtils.sanitiseStem(t.replace(" ", "_")))
                                  .orElse("Unknown_Title");

        return Optional.of(artistPart + "_-_" + titlePart);
    }
}
