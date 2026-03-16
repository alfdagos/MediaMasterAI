package it.alf.mediarenamer.enrichment;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import it.alf.mediarenamer.model.MediaMetadata;

/**
 * Immutable record that merges raw {@link MediaMetadata} with data obtained from external APIs.
 *
 * <p>Fields that could not be enriched remain {@link Optional#empty()}.</p>
 *
 * @param canonicalTitle  canonical title from TMDB (video) or MusicBrainz (audio)
 * @param releaseYear     release year from TMDB
 * @param artistName      canonical artist name from MusicBrainz
 * @param albumName       album name from MusicBrainz
 * @param city            city derived from GPS coordinates via Nominatim
 * @param country         country derived from GPS coordinates via Nominatim
 * @param additionalFields open-ended map for future enrichment sources
 */
public record EnrichedMetadata(
        Optional<String>  canonicalTitle,
        Optional<Integer> releaseYear,
        Optional<String>  artistName,
        Optional<String>  albumName,
        Optional<String>  city,
        Optional<String>  country,
        Map<String, String> additionalFields) {

    public EnrichedMetadata {
        Objects.requireNonNull(canonicalTitle,    "canonicalTitle must not be null");
        Objects.requireNonNull(releaseYear,       "releaseYear must not be null");
        Objects.requireNonNull(artistName,        "artistName must not be null");
        Objects.requireNonNull(albumName,         "albumName must not be null");
        Objects.requireNonNull(city,              "city must not be null");
        Objects.requireNonNull(country,           "country must not be null");
        additionalFields = Map.copyOf(Objects.requireNonNull(additionalFields));
    }

    /** Returns an empty {@code EnrichedMetadata} suitable as a no-op placeholder. */
    public static EnrichedMetadata empty() {
        return new EnrichedMetadata(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    /** Creates an {@code EnrichedMetadata} pre-populated from raw image metadata. */
    public static EnrichedMetadata fromImage(MediaMetadata.ImageMetadata meta) {
        return empty();  // GPS enrichment fills city/country after API call
    }

    /** Creates an {@code EnrichedMetadata} pre-populated from raw audio metadata. */
    public static EnrichedMetadata fromAudio(MediaMetadata.AudioMetadata meta) {
        return new EnrichedMetadata(
                meta.title(), Optional.empty(),
                meta.artist(), meta.album(),
                Optional.empty(), Optional.empty(), Map.of());
    }

    /** Creates an {@code EnrichedMetadata} pre-populated from raw video metadata. */
    public static EnrichedMetadata fromVideo(MediaMetadata.VideoMetadata meta) {
        return new EnrichedMetadata(
                meta.title(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of());
    }
}
