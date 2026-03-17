package it.alf.mediamaster.model;

/**
 * Enumerates the supported categories of media files.
 *
 * <p>The category is determined during scanning either by file extension
 * (fast path) or by inspecting magic bytes (fallback path).</p>
 */
public enum MediaType {

    /**
     * Still images: JPEG, PNG, HEIC, TIFF, WebP, RAW formats (CR2, NEF, ARW), etc.
     */
    IMAGE,

    /**
     * Video files: MP4, MOV, AVI, MKV, M4V, MTS, etc.
     */
    VIDEO,

    /**
     * Audio files: MP3, FLAC, AAC, OGG, WAV, M4A, OPUS, etc.
     */
    AUDIO
}
