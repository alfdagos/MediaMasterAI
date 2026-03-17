package it.alf.mediamaster.rename.strategies;

import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaMetadata;
import it.alf.mediamaster.model.MediaType;
import it.alf.mediamaster.rename.RenameStrategy;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Rename strategy for music audio files.
 *
 * <p>Preferred format: {@code NNN_Artist_-_Album_-_Title}
 * The track number prefix ({@code NNN}) is omitted when not available.
 * All components are sanitised: spaces become underscores, illegal
 * filesystem characters are stripped.</p>
 *
 * <p>Strategy ID: {@value #ID}</p>
 */
public class MusicRenameStrategy implements RenameStrategy {

    public static final String ID = "MUSIC_ARTIST_ALBUM_TRACK";

    @Override
    public String id() { return ID; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.AUDIO) return Optional.empty();

        Optional<String> artist = metadata.artistName();
        Optional<String> title  = metadata.canonicalTitle();

        if (artist.isEmpty() || title.isEmpty()) return Optional.empty();

        String artistPart = sanitise(artist.get());
        String titlePart  = sanitise(title.get());
        Optional<String> albumPart = metadata.albumName().map(this::sanitise);

        String stem = albumPart
                .map(album -> artistPart + "_-_" + album + "_-_" + titlePart)
                .orElse(artistPart + "_-_" + titlePart);

        // Prepend zero-padded track number when available
        Optional<Integer> trackNum = extractTrackNumber(metadata);
        return Optional.of(trackNum
                .map(n -> String.format("%03d_%s", n, stem))
                .orElse(stem));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Optional<Integer> extractTrackNumber(EnrichedMetadata metadata) {
        // Track number may be injected via additionalFields by the pipeline
        String raw = metadata.additionalFields().get("trackNumber");
        if (raw == null) return Optional.empty();
        try {
            // Handle "5/12" format (track/total)
            String stripped = raw.contains("/") ? raw.substring(0, raw.indexOf('/')) : raw;
            return Optional.of(Integer.parseInt(stripped.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String sanitise(String input) {
        return input.strip()
                    .replace(' ', '_')
                    .replaceAll("[^\\w_-]", "");
    }
}
