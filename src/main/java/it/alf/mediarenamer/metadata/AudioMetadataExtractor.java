package it.alf.mediarenamer.metadata;

import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.model.MediaMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Extracts {@link MediaMetadata.AudioMetadata} from audio files using the
 * <a href="https://bitbucket.org/ijabz/jaudiotagger">jaudiotagger</a> library.
 *
 * <p>Supported formats: MP3 (ID3v1, ID3v2), FLAC (Vorbis comment), AAC/M4A (iTunes tags),
 * OGG, WAV, WMA, OPUS.</p>
 *
 * <p>jaudiotagger uses {@code java.util.logging} internally; its verbose output is
 * suppressed to WARNING in the static initialiser below.</p>
 */
public class AudioMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(AudioMetadataExtractor.class);

    static {
        // Suppress jaudiotagger's excessively verbose internal logging
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    /**
     * Extracts audio metadata from the given {@link MediaFile}.
     *
     * <p>Missing tags result in {@link Optional#empty()} rather than exceptions.</p>
     *
     * @param mediaFile the audio file to inspect; must not be {@code null}
     * @return populated {@link MediaMetadata.AudioMetadata} record
     * @throws MetadataExtractionException if the file cannot be opened or parsed
     */
    public MediaMetadata.AudioMetadata extract(MediaFile mediaFile) throws MetadataExtractionException {
        Objects.requireNonNull(mediaFile, "mediaFile must not be null");

        Path path = mediaFile.path();
        log.debug("Extracting audio metadata from: {}", path);

        AudioFile audioFile = openAudioFile(path);
        Tag tag = audioFile.getTag();

        if (tag == null) {
            log.debug("No tags found in audio file: {}", path.getFileName());
            return emptyMetadata();
        }

        Optional<String>  title       = readField(tag, FieldKey.TITLE,        path);
        Optional<String>  artist      = readField(tag, FieldKey.ARTIST,       path);
        Optional<String>  album       = readField(tag, FieldKey.ALBUM,        path);
        Optional<String>  albumArtist = readField(tag, FieldKey.ALBUM_ARTIST, path);
        Optional<Integer> trackNumber = readIntField(tag, FieldKey.TRACK,     path);
        Optional<Integer> year        = readIntField(tag, FieldKey.YEAR,      path);
        Optional<String>  genre       = readField(tag, FieldKey.GENRE,        path);
        Optional<String>  mbid        = readField(tag, FieldKey.MUSICBRAINZ_TRACK_ID, path);

        log.debug("Audio metadata extracted from {}: artist={}, title={}",
                path.getFileName(),
                artist.orElse("<none>"),
                title.orElse("<none>"));

        return new MediaMetadata.AudioMetadata(title, artist, album, albumArtist,
                trackNumber, year, genre, mbid);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private AudioFile openAudioFile(Path path) throws MetadataExtractionException {
        try {
            return AudioFileIO.read(path.toFile());
        } catch (CannotReadException | InvalidAudioFrameException
                 | ReadOnlyFileException | TagException | IOException e) {
            throw new MetadataExtractionException(
                    "Cannot read audio metadata from: " + path, e);
        }
    }

    private Optional<String> readField(Tag tag, FieldKey key, Path path) {
        try {
            String value = tag.getFirst(key);
            return (value != null && !value.isBlank()) ? Optional.of(value.strip()) : Optional.empty();
        } catch (Exception e) {
            log.debug("Could not read field {} from {}: {}", key, path.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Integer> readIntField(Tag tag, FieldKey key, Path path) {
        return readField(tag, key, path).flatMap(value -> {
            try {
                // Track fields may be formatted as "5/12" — take only the track number part
                String numericPart = value.contains("/") ? value.split("/")[0] : value;
                return Optional.of(Integer.parseInt(numericPart.strip()));
            } catch (NumberFormatException e) {
                log.debug("Non-numeric value for field {} in {}: '{}'", key, path.getFileName(), value);
                return Optional.empty();
            }
        });
    }

    private MediaMetadata.AudioMetadata emptyMetadata() {
        return new MediaMetadata.AudioMetadata(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
