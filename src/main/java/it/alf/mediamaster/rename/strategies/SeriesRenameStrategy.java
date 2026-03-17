package it.alf.mediamaster.rename.strategies;

import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaType;
import it.alf.mediamaster.rename.RenameStrategy;
import it.alf.mediamaster.series.SeriesParser;
import it.alf.mediamaster.series.SeriesParser.SeriesInfo;

import java.util.Objects;
import java.util.Optional;

/**
 * Rename strategy for TV-series episode files.
 *
 * <p>Template: {@code Show_Name_S02E03[_Episode_Title]}
 * The episode title is appended when available from enriched metadata or from
 * the original filename (via {@link SeriesParser}).</p>
 *
 * <p>Strategy ID: {@value #ID}</p>
 */
public class SeriesRenameStrategy implements RenameStrategy {

    public static final String ID = "SERIES_SEASON_EPISODE";

    private final SeriesParser seriesParser;

    /**
     * Creates a {@code SeriesRenameStrategy} with the given parser.
     *
     * @param seriesParser parser for extracting series info from filenames; must not be null
     */
    public SeriesRenameStrategy(SeriesParser seriesParser) {
        this.seriesParser = Objects.requireNonNull(seriesParser, "seriesParser must not be null");
    }

    /** Creates a {@code SeriesRenameStrategy} with a default {@link SeriesParser}. */
    public SeriesRenameStrategy() {
        this(new SeriesParser());
    }

    @Override
    public String id() { return ID; }

    @Override
    public Optional<String> proposeStem(MediaFile file, EnrichedMetadata metadata) {
        if (file.type() != MediaType.VIDEO) return Optional.empty();

        // First try to parse the filename directly
        Optional<SeriesInfo> parsed = seriesParser.parse(file.filename());
        if (parsed.isEmpty()) return Optional.empty();

        SeriesInfo info = parsed.get();

        // Prefer enriched episode title from API when available
        Optional<String> episodeTitle = metadata.additionalFields().containsKey("episodeTitle")
                ? Optional.of(metadata.additionalFields().get("episodeTitle"))
                : info.episodeTitle();

        SeriesInfo enrichedInfo = new SeriesInfo(
                metadata.canonicalTitle().orElse(info.showName()),
                info.season(),
                info.episode(),
                episodeTitle);

        return Optional.of(seriesParser.buildStem(enrichedInfo));
    }
}
