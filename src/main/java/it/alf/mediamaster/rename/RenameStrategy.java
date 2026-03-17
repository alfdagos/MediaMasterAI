package it.alf.mediamaster.rename;

import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.model.MediaFile;

import java.util.Optional;

/**
 * Strategy interface for generating new filename stems.
 *
 * <p>Each implementation encodes one specific naming convention
 * (e.g. {@code DATE_LOCATION}, {@code MOVIE_TITLE_YEAR}).
 * The {@link SmartRenameEngine} iterates its configured strategy list and
 * uses the first one that returns a non-empty result for the current file.</p>
 *
 * <p>Implementations must be:</p>
 * <ul>
 *   <li><b>Stateless</b> — safe for concurrent use and reuse across files</li>
 *   <li><b>Pure</b> — must not perform I/O or modify any external state</li>
 *   <li><b>Null-safe</b> — must handle {@code Optional.empty()} fields in metadata gracefully</li>
 * </ul>
 *
 * <p>The engine appends the lowercased original extension; strategies return the stem only.</p>
 */
public interface RenameStrategy {

    /**
     * A short, uppercase identifier used in logs and reports.
     * Examples: {@code "DATE_LOCATION"}, {@code "MOVIE_TITLE_YEAR"}.
     *
     * @return non-null, non-blank strategy identifier
     */
    String id();

    /**
     * Proposes a new filename stem for the given file and its enriched metadata.
     *
     * @param file     the media file being renamed; never {@code null}
     * @param metadata enriched metadata for the file; never {@code null}
     * @return proposed stem (no extension, no directory component),
     *         or {@link Optional#empty()} if this strategy cannot handle the file
     */
    Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata);
}
