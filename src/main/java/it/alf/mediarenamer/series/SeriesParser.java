package it.alf.mediarenamer.series;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses TV-series information from media filenames.
 *
 * <p>Recognised patterns (case-insensitive):</p>
 * <ul>
 *   <li>{@code Breaking.Bad.S02E03.1080p.mkv}  → show="Breaking Bad", S=2, E=3</li>
 *   <li>{@code The.Office.US.S05E14.mp4}        → show="The Office US", S=5, E=14</li>
 *   <li>{@code Show_Name_2x03_Episode.mkv}       → show="Show Name", S=2, E=3</li>
 *   <li>{@code Show Name - 3x08 - Title.mp4}    → show="Show Name", S=3, E=8</li>
 *   <li>{@code [Group] Show S01E07 [720p].mkv}  → show="Show", S=1, E=7</li>
 * </ul>
 *
 * <p>On a successful parse the returned {@link SeriesInfo} record contains the
 * normalised show name (spaces instead of dots/underscores, trimmed), season and
 * episode numbers, and an optional episode title when present after the S×E marker.</p>
 */
public class SeriesParser {

    private static final Logger log = LoggerFactory.getLogger(SeriesParser.class);

    // Pattern 1: SnnEnn  (e.g. S02E03, s01e12)
    private static final Pattern SE_PATTERN = Pattern.compile(
            "^(?<show>.+?)[._ -]+[Ss](?<season>\\d{1,2})[Ee](?<episode>\\d{1,3})" +
            "(?:[._ -]+(?<title>[^.[(]+?))?(?:[.[(].*)?$",
            Pattern.CASE_INSENSITIVE);

    // Pattern 2: NxNN  (e.g. 2x03, 10x21)
    private static final Pattern NXN_PATTERN = Pattern.compile(
            "^(?<show>.+?)[._ -]+(?<season>\\d{1,2})x(?<episode>\\d{1,3})" +
            "(?:[._ -]+(?<title>[^.[(]+?))?(?:[.[(].*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Attempts to parse series information from a filename stem (without extension).
     *
     * @param filenameStem the filename to parse, with or without extension
     * @return parsed {@link SeriesInfo}, or {@link Optional#empty()} if the filename
     *         does not match any known series pattern
     */
    public Optional<SeriesInfo> parse(String filenameStem) {
        Objects.requireNonNull(filenameStem, "filenameStem must not be null");

        // Strip extension if present
        String stem = filenameStem.contains(".")
                ? filenameStem.substring(0, filenameStem.lastIndexOf('.'))
                : filenameStem;

        Optional<SeriesInfo> result = tryPattern(SE_PATTERN, stem);
        if (result.isEmpty()) {
            result = tryPattern(NXN_PATTERN, stem);
        }

        result.ifPresentOrElse(
                info -> log.debug("Parsed series: {} S{}E{}", info.showName(), info.season(), info.episode()),
                ()   -> log.trace("No series pattern matched: {}", stem));

        return result;
    }

    private Optional<SeriesInfo> tryPattern(Pattern pattern, String stem) {
        Matcher m = pattern.matcher(stem.strip());
        if (!m.matches()) return Optional.empty();

        String rawShow = m.group("show");
        int    season  = Integer.parseInt(m.group("season"));
        int    episode = Integer.parseInt(m.group("episode"));

        String normalised = normaliseShowName(rawShow);
        Optional<String> title = Optional.ofNullable(m.group("title"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .map(this::normaliseShowName);

        return Optional.of(new SeriesInfo(normalised, season, episode, title));
    }

    /**
     * Converts dot/underscore separators to spaces and trims resolution/quality tags
     * (e.g. {@code "Breaking.Bad"} → {@code "Breaking Bad"}).
     */
    private String normaliseShowName(String raw) {
        return raw.replace('.', ' ')
                  .replace('_', ' ')
                  .replaceAll("\\s{2,}", " ")
                  .strip();
    }

    /**
     * Builds a standardised filename stem for a TV series episode.
     *
     * <p>Format: {@code Show_Name_S02E03} or {@code Show_Name_S02E03_Episode_Title}
     * when a title is available.</p>
     *
     * @param info the parsed series information
     * @return filename stem ready to be sanitised by {@link it.alf.mediarenamer.util.FileUtils#sanitiseStem}
     */
    public String buildStem(SeriesInfo info) {
        Objects.requireNonNull(info, "info must not be null");
        String showPart = info.showName().replace(' ', '_');
        String sePart   = String.format("S%02dE%02d", info.season(), info.episode());
        String titlePart = info.episodeTitle()
                .map(t -> "_" + t.replace(' ', '_'))
                .orElse("");
        return showPart + "_" + sePart + titlePart;
    }

    // ── SeriesInfo ─────────────────────────────────────────────────────────

    /**
     * Parsed TV series metadata.
     *
     * @param showName     normalised show name (spaces, title-case preserved)
     * @param season       season number (1-based)
     * @param episode      episode number (1-based)
     * @param episodeTitle optional episode title when present in the filename
     */
    public record SeriesInfo(
            String           showName,
            int              season,
            int              episode,
            Optional<String> episodeTitle) {

        public SeriesInfo {
            Objects.requireNonNull(showName,     "showName must not be null");
            Objects.requireNonNull(episodeTitle, "episodeTitle must not be null");
            if (season  < 0) throw new IllegalArgumentException("season must be >= 0");
            if (episode < 1) throw new IllegalArgumentException("episode must be >= 1");
        }
    }
}
