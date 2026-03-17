package it.alf.mediamaster.rename;

import it.alf.mediamaster.enrichment.EnrichedMetadata;
import it.alf.mediamaster.enrichment.MetadataEnrichmentService;
import it.alf.mediamaster.metadata.AudioMetadataExtractor;
import it.alf.mediamaster.metadata.ImageMetadataExtractor;
import it.alf.mediamaster.metadata.MetadataExtractionException;
import it.alf.mediamaster.metadata.VideoMetadataExtractor;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaMetadata;
import it.alf.mediamaster.model.MediaType;
import it.alf.mediamaster.series.SeriesParser;
import it.alf.mediamaster.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Orchestrates the full rename pipeline for a list of media files.
 *
 * <p>For each file the engine:</p>
 * <ol>
 *   <li>Extracts raw metadata using the appropriate extractor</li>
 *   <li>Enriches metadata via external APIs if enrichment is enabled</li>
 *   <li>Selects the first {@link RenameStrategy} that produces a non-empty stem</li>
 *   <li>Sanitises the stem and resolves collisions</li>
 *   <li>Returns a {@link RenameProposal} without touching the filesystem</li>
 * </ol>
 *
 * <p>When {@link RenameOptions#dryRun()} is {@code false} and
 * {@link #executeProposals} is called, renames are performed atomically and
 * every operation is recorded in the undo journal.</p>
 */
public class SmartRenameEngine {

    private static final Logger log = LoggerFactory.getLogger(SmartRenameEngine.class);

    private final ImageMetadataExtractor    imageExtractor;
    private final AudioMetadataExtractor    audioExtractor;
    private final VideoMetadataExtractor    videoExtractor;
    private final MetadataEnrichmentService enrichmentService;

    private final DateLocationStrategy      dateLocationStrategy   = new DateLocationStrategy();
    private final MovieTitleYearStrategy    movieTitleYearStrategy = new MovieTitleYearStrategy();
    private final ArtistTrackNumberedStrategy artistTrackStrategy  = new ArtistTrackNumberedStrategy();
    private final DateOnlyStrategy          dateOnlyStrategy       = new DateOnlyStrategy();

    /**
     * Creates a new engine with a default enrichment service.
     */
    public SmartRenameEngine() {
        this(new ImageMetadataExtractor(),
             new AudioMetadataExtractor(),
             new VideoMetadataExtractor(),
             new MetadataEnrichmentService());
    }

    /**
     * Creates a new engine with injected dependencies (suitable for testing).
     */
    public SmartRenameEngine(ImageMetadataExtractor imageExtractor,
                              AudioMetadataExtractor audioExtractor,
                              VideoMetadataExtractor videoExtractor,
                              MetadataEnrichmentService enrichmentService) {
        this.imageExtractor    = Objects.requireNonNull(imageExtractor);
        this.audioExtractor    = Objects.requireNonNull(audioExtractor);
        this.videoExtractor    = Objects.requireNonNull(videoExtractor);
        this.enrichmentService = Objects.requireNonNull(enrichmentService);
    }

    // ── Proposal generation ────────────────────────────────────────────────

    /**
     * Generates rename proposals for all supplied media files.
     *
     * <p>Files for which no strategy produces a stem are represented as
     * {@link RenameProposal.Skipped} entries.</p>
     *
     * @param files   files to process
     * @param options rename configuration
     * @return list of proposals in input order
     */
    public List<RenameProposal> propose(List<MediaFile> files, RenameOptions options) {
        Objects.requireNonNull(files,   "files must not be null");
        Objects.requireNonNull(options, "options must not be null");

        log.info("Generating rename proposals for {} files (strategy hint: {})",
                files.size(), options.strategyId().orElse("auto"));

        List<RenameProposal> proposals = new ArrayList<>(files.size());
        for (MediaFile file : files) {
            proposals.add(proposeForFile(file, options));
        }
        return Collections.unmodifiableList(proposals);
    }

    private RenameProposal proposeForFile(MediaFile file, RenameOptions options) {
        // ── 1. Extract raw metadata ──────────────────────────────────────
        MediaMetadata rawMetadata;
        try {
            rawMetadata = extractMetadata(file);
        } catch (MetadataExtractionException e) {
            log.warn("Metadata extraction failed for {}: {}", file.path(), e.getMessage());
            return RenameProposal.skipped(file, "metadata extraction failed: " + e.getMessage());
        }

        // ── 2. Enrich ────────────────────────────────────────────────────
        EnrichedMetadata enriched;
        if (options.enrichmentEnabled()) {
            try {
                enriched = enrichmentService.enrich(file, rawMetadata);
            } catch (Exception e) {
                log.warn("Enrichment failed for {}: {}; proceeding without enrichment",
                        file.path(), e.getMessage());
                enriched = EnrichedMetadata.empty();
            }
        } else {
            enriched = buildEnrichedFromRaw(rawMetadata);
        }

        // ── 3. Select strategy and generate stem ─────────────────────────
        Optional<String> stem = options.plexMode()
                ? generatePlexStem(file, rawMetadata, enriched)
                : generateStem(file, rawMetadata, enriched, options);
        if (stem.isEmpty()) {
            log.debug("No strategy produced a stem for: {}", file.path());
            return RenameProposal.skipped(file, "no applicable rename strategy");
        }

        // ── 4. Build target path ─────────────────────────────────────────
        String sanitised = FileUtils.sanitiseStem(stem.get());
        Path targetPath  = FileUtils.resolveCollisionFreePath(
                file.path().getParent(), sanitised, file.extension());

        log.debug("Proposal: {} → {}", file.path().getFileName(), targetPath.getFileName());
        return RenameProposal.approved(file, targetPath, strategyIdForFile(file, rawMetadata, enriched, options));
    }

    // ── Stem generation ────────────────────────────────────────────────────

    private Optional<String> generateStem(MediaFile file, MediaMetadata raw,
                                           EnrichedMetadata enriched, RenameOptions options) {
        String strategyHint = options.strategyId().orElse("auto");

        if (file.type() == MediaType.IMAGE && raw instanceof MediaMetadata.ImageMetadata imgMeta) {
            if ("auto".equals(strategyHint) || "DATE_LOCATION".equals(strategyHint)) {
                Optional<String> s = dateLocationStrategy.proposeStem(file, imgMeta, enriched);
                if (s.isPresent()) return s;
            }
            // fallback: DATE_ONLY
            Optional<LocalDateTime> date = imgMeta.captureDate();
            return dateOnlyStrategy.proposeStem(date);
        }

        if (file.type() == MediaType.VIDEO) {
            if ("auto".equals(strategyHint) || "MOVIE_TITLE_YEAR".equals(strategyHint)) {
                Optional<String> s = movieTitleYearStrategy.proposeStem(file, enriched);
                if (s.isPresent()) return s;
            }
            // fallback: DATE_ONLY from video creation date
            if (raw instanceof MediaMetadata.VideoMetadata vidMeta) {
                return dateOnlyStrategy.proposeStem(vidMeta.creationDate());
            }
        }

        if (file.type() == MediaType.AUDIO) {
            if ("auto".equals(strategyHint) || "ARTIST_TRACK".equals(strategyHint)) {
                Optional<Integer> trackNo = Optional.empty();
                if (raw instanceof MediaMetadata.AudioMetadata audMeta) {
                    trackNo = audMeta.trackNumber();
                }
                Optional<String> s = artistTrackStrategy.proposeStemWithTrack(file, enriched, trackNo);
                if (s.isPresent()) return s;
            }
        }
        return Optional.empty();
    }

    private String strategyIdForFile(MediaFile file, MediaMetadata raw,
                                      EnrichedMetadata enriched, RenameOptions options) {
        return options.strategyId().orElse(switch (file.type()) {
            case IMAGE -> "DATE_LOCATION";
            case VIDEO -> options.plexMode() ? "PLEX_VIDEO" : "MOVIE_TITLE_YEAR";
            case AUDIO -> options.plexMode() ? "PLEX_AUDIO" : "ARTIST_TRACK";
        });
    }

    // ── Metadata extraction dispatch ───────────────────────────────────────

    private MediaMetadata extractMetadata(MediaFile file) {
        return switch (file.type()) {
            case IMAGE -> imageExtractor.extract(file);
            case AUDIO -> audioExtractor.extract(file);
            case VIDEO -> videoExtractor.extract(file);
        };
    }

    private EnrichedMetadata buildEnrichedFromRaw(MediaMetadata raw) {
        return switch (raw) {
            case MediaMetadata.ImageMetadata img -> EnrichedMetadata.fromImage(img);
            case MediaMetadata.AudioMetadata aud -> EnrichedMetadata.fromAudio(aud);
            case MediaMetadata.VideoMetadata vid -> EnrichedMetadata.fromVideo(vid);
        };
    }

    // ── Plex stem generation ───────────────────────────────────────────────

    /**
     * Generates a Plex-compatible filename stem for the given file.
     *
     * <p>Plex conventions:</p>
     * <ul>
     *   <li>Movies: {@code Movie Title (2014)}</li>
     *   <li>TV episodes: {@code Show Name - S01E02 - Episode Title}</li>
     *   <li>Music: {@code NN - Track Title} (artist/album handled by folder structure)</li>
     *   <li>Images: unchanged from standard naming</li>
     * </ul>
     */
    private Optional<String> generatePlexStem(MediaFile file, MediaMetadata raw, EnrichedMetadata enriched) {
        return switch (file.type()) {
            case IMAGE -> generateStem(file, raw, enriched,
                    new RenameOptions(Optional.empty(), RenameOptions.CollisionPolicy.SKIP, false, true, false));
            case VIDEO -> generatePlexVideoStem(file, enriched);
            case AUDIO -> generatePlexAudioStem(raw, enriched);
        };
    }

    private Optional<String> generatePlexVideoStem(MediaFile file, EnrichedMetadata enriched) {
        // Try TV series detection first (SnnEnn / NxNN patterns)
        SeriesParser parser = new SeriesParser();
        Optional<SeriesParser.SeriesInfo> seriesInfo = parser.parse(file.filename());
        if (seriesInfo.isPresent()) {
            SeriesParser.SeriesInfo info = seriesInfo.get();
            String showName = enriched.canonicalTitle()
                    .orElse(info.showName().replace('_', ' '));
            String code = String.format("S%02dE%02d", info.season(), info.episode());
            Optional<String> episodeTitle = enriched.additionalFields().containsKey("episodeTitle")
                    ? Optional.of(enriched.additionalFields().get("episodeTitle"))
                    : info.episodeTitle();
            String stem = episodeTitle.isPresent()
                    ? showName + " - " + code + " - " + episodeTitle.get()
                    : showName + " - " + code;
            return Optional.of(stem);
        }
        // Movie: "Movie Title (2014)"
        return enriched.canonicalTitle().map(title ->
                enriched.releaseYear()
                        .map(year -> title + " (" + year + ")")
                        .orElse(title));
    }

    private Optional<String> generatePlexAudioStem(MediaMetadata raw, EnrichedMetadata enriched) {
        // Plex music: "NN - Track Title" (artist & album resolved from folder structure by Plex)
        Optional<String> title = enriched.canonicalTitle();
        if (title.isEmpty()) return Optional.empty();

        Optional<Integer> trackNo = Optional.empty();
        if (raw instanceof MediaMetadata.AudioMetadata audMeta) {
            trackNo = audMeta.trackNumber();
        }
        if (trackNo.isEmpty()) {
            String rawStr = enriched.additionalFields().get("trackNumber");
            if (rawStr != null) {
                try {
                    String stripped = rawStr.contains("/")
                            ? rawStr.substring(0, rawStr.indexOf('/')) : rawStr;
                    trackNo = Optional.of(Integer.parseInt(stripped.strip()));
                } catch (NumberFormatException ignored) { }
            }
        }
        final Optional<Integer> finalTrackNo = trackNo;
        return Optional.of(finalTrackNo
                .map(n -> String.format("%02d - %s", n, title.get()))
                .orElse(title.get()));
    }

    // ── Proposal execution ─────────────────────────────────────────────────

    /**
     * Executes the approved proposals and returns a list of {@link RenameResult} objects.
     *
     * <p>Each rename is performed using {@link Files#move} with
     * {@link StandardCopyOption#ATOMIC_MOVE} when source and target are on the same
     * filesystem. The undo journal is updated for every successful rename.</p>
     *
     * @param proposals   list produced by {@link #propose}
     * @param undoJournal the undo journal to record operations into
     * @param dryRun      when {@code true} the filesystem is not modified
     * @return execution results in input order
     */
    public List<RenameResult> executeProposals(List<RenameProposal> proposals,
                                               UndoJournal undoJournal,
                                               boolean dryRun) {
        Objects.requireNonNull(proposals,   "proposals must not be null");
        Objects.requireNonNull(undoJournal, "undoJournal must not be null");

        String sessionId = java.util.UUID.randomUUID().toString();
        log.info("Executing {} proposals (dryRun={}, session={})",
                proposals.size(), dryRun, sessionId);

        List<RenameResult> results = new ArrayList<>(proposals.size());
        for (RenameProposal proposal : proposals) {
            results.add(executeOne(proposal, undoJournal, sessionId, dryRun));
        }
        return Collections.unmodifiableList(results);
    }

    private RenameResult executeOne(RenameProposal proposal, UndoJournal journal,
                                     String sessionId, boolean dryRun) {
        if (proposal instanceof RenameProposal.Skipped skipped) {
            return new RenameResult.Skipped(skipped.file(), skipped.reason());
        }

        RenameProposal.Approved approved = (RenameProposal.Approved) proposal;
        Path source = approved.file().path();
        Path target = approved.targetPath();

        if (dryRun) {
            log.debug("[DRY-RUN] Would rename: {} → {}", source.getFileName(), target.getFileName());
            return new RenameResult.DryRun(approved.file(), target, approved.strategyUsed());
        }

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            journal.record(new UndoJournal.Operation(
                    Instant.now(), source, target, approved.strategyUsed(), sessionId));
            log.info("Renamed: {} → {}", source, target);
            return new RenameResult.Success(approved.file(), target, approved.strategyUsed());
        } catch (AtomicMoveNotSupportedException e) {
            // Cross-filesystem: fall back to copy-then-delete
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source);
                journal.record(new UndoJournal.Operation(
                        Instant.now(), source, target, approved.strategyUsed(), sessionId));
                log.info("Renamed (copy+delete): {} → {}", source, target);
                return new RenameResult.Success(approved.file(), target, approved.strategyUsed());
            } catch (IOException ex) {
                log.error("Rename failed for {}: {}", source, ex.getMessage(), ex);
                return new RenameResult.Failed(approved.file(), ex);
            }
        } catch (IOException e) {
            log.error("Rename failed for {}: {}", source, e.getMessage(), e);
            return new RenameResult.Failed(approved.file(), e);
        }
    }
}
