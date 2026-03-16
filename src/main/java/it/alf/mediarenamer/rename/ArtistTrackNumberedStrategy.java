package it.alf.mediarenamer.rename;

import java.util.Optional;

import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.model.MediaType;

/**
 * {@code ARTIST_TRACK_NUMBERED} — like {@link ArtistTrackStrategy} but prepends a
 * zero-padded track number when available.
 *
 * <p>Example: {@code 05_Pink_Floyd_-_Comfortably_Numb.mp3}</p>
 */
public class ArtistTrackNumberedStrategy implements RenameStrategy {

    private final ArtistTrackStrategy base = new ArtistTrackStrategy();

    @Override
    public String id() { return "ARTIST_TRACK_NUMBERED"; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        return base.proposeStem(file, metadata);
    }

    /**
     * Generates a stem with an optional zero-padded track-number prefix.
     *
     * @param file        the audio file
     * @param metadata    enriched metadata
     * @param trackNumber optional track number (e.g. from raw audio tags)
     * @return proposed stem, or empty when base strategy cannot match
     */
    public Optional<String> proposeStemWithTrack(MediaFile file,
                                                  EnrichedMetadata metadata,
                                                  Optional<Integer> trackNumber) {
        if (file.type() != MediaType.AUDIO) return Optional.empty();
        return base.proposeStem(file, metadata).map(stem ->
                trackNumber.map(n -> String.format("%02d_%s", n, stem)).orElse(stem));
    }
}
