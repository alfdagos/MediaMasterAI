package it.alf.mediamaster.metadata;

/**
 * Thrown when metadata cannot be extracted from a media file.
 *
 * <p>This is an unchecked exception. It wraps low-level library exceptions
 * ({@code ImageProcessingException}, {@code CannotReadException}, {@code IOException})
 * into a consistent domain exception.</p>
 */
public class MetadataExtractionException extends RuntimeException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message description of what failed and which file was involved
     */
    public MetadataExtractionException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and root cause.
     *
     * @param message description of what failed and which file was involved
     * @param cause   the underlying library or I/O exception
     */
    public MetadataExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
