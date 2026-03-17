package it.alf.mediamaster.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Sealed hierarchy representing raw metadata extracted from a {@link MediaFile}.
 *
 * <p>Use pattern matching to dispatch on the concrete type:</p>
 * <pre>{@code
 * switch (metadata) {
 *     case ImageMetadata img -> processImage(img);
 *     case AudioMetadata aud -> processAudio(aud);
 *     case VideoMetadata vid -> processVideo(vid);
 * }
 * }</pre>
 */
public sealed interface MediaMetadata
        permits MediaMetadata.ImageMetadata,
                MediaMetadata.AudioMetadata,
                MediaMetadata.VideoMetadata {

    // ─────────────────────────────────────────────────────────────
    //  Image
    // ─────────────────────────────────────────────────────────────

    /**
     * Metadata extracted from a still image (EXIF, IPTC, XMP).
     *
     * @param captureDate  date and time when the shutter was released, if present in EXIF
     * @param gpsLatitude  GPS latitude in decimal degrees (positive = North), if present
     * @param gpsLongitude GPS longitude in decimal degrees (positive = East), if present
     * @param cameraMake   camera manufacturer (e.g. "Canon", "Apple"), if present
     * @param cameraModel  camera model string (e.g. "iPhone 15 Pro"), if present
     * @param width        image width in pixels, if known
     * @param height       image height in pixels, if known
     */
    record ImageMetadata(
            Optional<LocalDateTime> captureDate,
            Optional<Double> gpsLatitude,
            Optional<Double> gpsLongitude,
            Optional<String> cameraMake,
            Optional<String> cameraModel,
            Optional<Integer> width,
            Optional<Integer> height
    ) implements MediaMetadata {

        /** Returns {@code true} if both GPS coordinates are present. */
        public boolean hasGpsCoordinates() {
            return gpsLatitude.isPresent() && gpsLongitude.isPresent();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Audio
    // ─────────────────────────────────────────────────────────────

    /**
     * Metadata extracted from an audio file (ID3, Vorbis comment, iTunes tags).
     *
     * @param title            track title, if present
     * @param artist           primary artist, if present
     * @param album            album name, if present
     * @param albumArtist      album artist (may differ from track artist), if present
     * @param trackNumber      track number within the album, if present
     * @param year             release year, if present
     * @param genre            genre string, if present
     * @param musicBrainzTrackId MusicBrainz recording ID (MBID), if tagged
     */
    record AudioMetadata(
            Optional<String> title,
            Optional<String> artist,
            Optional<String> album,
            Optional<String> albumArtist,
            Optional<Integer> trackNumber,
            Optional<Integer> year,
            Optional<String> genre,
            Optional<String> musicBrainzTrackId
    ) implements MediaMetadata {}

    // ─────────────────────────────────────────────────────────────
    //  Video
    // ─────────────────────────────────────────────────────────────

    /**
     * Metadata extracted from a video file (MP4/MOV atoms, MKV EBML header, etc.).
     *
     * @param creationDate date and time the video was recorded, if present in the container
     * @param title        title tag embedded in the container, if present
     * @param duration     total playback duration, if known
     * @param width        video width in pixels, if known
     * @param height       video height in pixels, if known
     */
    record VideoMetadata(
            Optional<LocalDateTime> creationDate,
            Optional<String> title,
            Optional<Duration> duration,
            Optional<Integer> width,
            Optional<Integer> height
    ) implements MediaMetadata {}
}
