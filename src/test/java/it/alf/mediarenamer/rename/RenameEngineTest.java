package it.alf.mediarenamer.rename;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaFile;
import it.alf.mediarenamer.model.MediaMetadata;
import it.alf.mediarenamer.model.MediaType;
import it.alf.mediarenamer.util.FileUtils;

/**
 * Unit tests for the rename engine, strategies, proposal model and undo journal.
 *
 * <p>All tests use {@code @TempDir} to keep the real filesystem untouched, and
 * no external APIs are called.</p>
 */
class RenameEngineTest {

    @TempDir
    Path tempDir;

    // ── Helpers ────────────────────────────────────────────────────────────

    private MediaFile imageFile(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "fake-image");
        return new MediaFile(p, MediaType.IMAGE, 10L, FileTime.from(Instant.now()));
    }

    private MediaFile audioFile(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "fake-audio");
        return new MediaFile(p, MediaType.AUDIO, 10L, FileTime.from(Instant.now()));
    }

    private MediaFile videoFile(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "fake-video");
        return new MediaFile(p, MediaType.VIDEO, 10L, FileTime.from(Instant.now()));
    }

    private MediaMetadata.ImageMetadata imageMetaWithDate(LocalDateTime date) {
        return new MediaMetadata.ImageMetadata(
                Optional.of(date),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private MediaMetadata.AudioMetadata audioMetaWithTags(String artist, String title, int track) {
        return new MediaMetadata.AudioMetadata(
                Optional.of(title),
                Optional.of(artist),
                Optional.empty(),
                Optional.empty(),
                Optional.of(track),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private MediaMetadata.VideoMetadata videoMetaWithDate(LocalDateTime date) {
        return new MediaMetadata.VideoMetadata(
                Optional.of(date),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DateLocationStrategy
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DateLocationStrategy")
    class DateLocationStrategyTest {

        private final DateLocationStrategy strategy = new DateLocationStrategy();

        @Test
        @DisplayName("should propose date stem when image has EXIF date")
        void shouldProposeDateStem_whenImageHasExifDate() throws IOException {
            MediaFile file  = imageFile("photo.jpg");
            LocalDateTime dt = LocalDateTime.of(2024, 3, 15, 14, 32, 7);
            MediaMetadata.ImageMetadata meta = imageMetaWithDate(dt);

            Optional<String> stem = strategy.proposeStem(file, meta, EnrichedMetadata.empty());

            assertThat(stem).isPresent();
            assertThat(stem.get()).startsWith("2024-03-15_14-32-07");
        }

        @Test
        @DisplayName("should append location when city and country are available")
        void shouldAppendLocation_whenCityAndCountryPresent() throws IOException {
            MediaFile file  = imageFile("photo.jpg");
            LocalDateTime dt = LocalDateTime.of(2024, 6, 1, 9, 0, 0);
            MediaMetadata.ImageMetadata meta = imageMetaWithDate(dt);
            EnrichedMetadata enriched = new EnrichedMetadata(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of("Rome"), Optional.of("Italy"), Map.of());

            Optional<String> stem = strategy.proposeStem(file, meta, enriched);

            assertThat(stem).isPresent();
            assertThat(stem.get()).endsWith("_Rome-Italy");
        }

        @Test
        @DisplayName("should return empty when image has no capture date")
        void shouldReturnEmpty_whenNoCaptureDate() throws IOException {
            MediaFile file = imageFile("photo.jpg");
            MediaMetadata.ImageMetadata meta = new MediaMetadata.ImageMetadata(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

            Optional<String> stem = strategy.proposeStem(file, meta, EnrichedMetadata.empty());

            assertThat(stem).isEmpty();
        }

        @Test
        @DisplayName("should return empty for non-IMAGE type")
        void shouldReturnEmpty_forNonImageType() throws IOException {
            MediaFile file = audioFile("song.mp3");
            MediaMetadata.ImageMetadata meta = imageMetaWithDate(LocalDateTime.now());

            Optional<String> stem = strategy.proposeStem(file, meta, EnrichedMetadata.empty());

            assertThat(stem).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MovieTitleYearStrategy
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MovieTitleYearStrategy")
    class MovieTitleYearStrategyTest {

        private final MovieTitleYearStrategy strategy = new MovieTitleYearStrategy();

        @Test
        @DisplayName("should propose title + year when both are present")
        void shouldProposeTitleAndYear_whenBothPresent() throws IOException {
            MediaFile file = videoFile("movie.mp4");
            EnrichedMetadata enriched = new EnrichedMetadata(
                    Optional.of("The Grand Budapest Hotel"),
                    Optional.of(2014),
                    Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Map.of());

            Optional<String> stem = strategy.proposeStem(file, enriched);

            assertThat(stem).isPresent();
            assertThat(stem.get()).isEqualTo("The_Grand_Budapest_Hotel_2014");
        }

        @Test
        @DisplayName("should omit year when not available")
        void shouldOmitYear_whenNotAvailable() throws IOException {
            MediaFile file = videoFile("movie.mkv");
            EnrichedMetadata enriched = new EnrichedMetadata(
                    Optional.of("Unknown Movie"), Optional.empty(),
                    Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Map.of());

            Optional<String> stem = strategy.proposeStem(file, enriched);

            assertThat(stem).isPresent();
            assertThat(stem.get()).isEqualTo("Unknown_Movie");
        }

        @Test
        @DisplayName("should return empty when canonical title is absent")
        void shouldReturnEmpty_whenNoCandidateTitle() throws IOException {
            MediaFile file = videoFile("video.mp4");
            Optional<String> stem = strategy.proposeStem(file, EnrichedMetadata.empty());
            assertThat(stem).isEmpty();
        }

        @Test
        @DisplayName("should return empty for non-VIDEO type")
        void shouldReturnEmpty_forNonVideoType() throws IOException {
            MediaFile file = imageFile("photo.jpg");
            EnrichedMetadata enriched = new EnrichedMetadata(
                    Optional.of("The Matrix"), Optional.of(1999),
                    Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Map.of());

            Optional<String> stem = strategy.proposeStem(file, enriched);

            assertThat(stem).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ArtistTrackNumberedStrategy
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ArtistTrackNumberedStrategy")
    class ArtistTrackNumberedStrategyTest {

        private final ArtistTrackNumberedStrategy strategy = new ArtistTrackNumberedStrategy();

        @Test
        @DisplayName("should propose numbered stem when track number is available")
        void shouldProposeNumberedStem_whenTrackNumberPresent() throws IOException {
            MediaFile file = audioFile("song.mp3");
            EnrichedMetadata enriched = new EnrichedMetadata(
                    Optional.of("Comfortably Numb"), Optional.empty(),
                    Optional.of("Pink Floyd"), Optional.empty(),
                    Optional.empty(), Optional.empty(), Map.of());

            Optional<String> stem = strategy.proposeStemWithTrack(file, enriched, Optional.of(5));

            assertThat(stem).isPresent();
            assertThat(stem.get()).isEqualTo("05_Pink_Floyd_-_Comfortably_Numb");
        }

        @Test
        @DisplayName("should omit number prefix when track number is absent")
        void shouldOmitNumber_whenTrackNumberAbsent() throws IOException {
            MediaFile file = audioFile("song.flac");
            EnrichedMetadata enriched = new EnrichedMetadata(
                    Optional.of("Bohemian Rhapsody"), Optional.empty(),
                    Optional.of("Queen"), Optional.empty(),
                    Optional.empty(), Optional.empty(), Map.of());

            Optional<String> stem = strategy.proposeStemWithTrack(file, enriched, Optional.empty());

            assertThat(stem).isPresent();
            assertThat(stem.get()).isEqualTo("Queen_-_Bohemian_Rhapsody");
        }

        @Test
        @DisplayName("should return empty for non-AUDIO type")
        void shouldReturnEmpty_forNonAudioType() throws IOException {
            MediaFile file = videoFile("video.mp4");
            Optional<String> stem = strategy.proposeStemWithTrack(file, EnrichedMetadata.empty(), Optional.of(1));
            assertThat(stem).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DateOnlyStrategy
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DateOnlyStrategy")
    class DateOnlyStrategyTest {

        private final DateOnlyStrategy strategy = new DateOnlyStrategy();

        @Test
        @DisplayName("should produce ISO-like formatted date stem")
        void shouldProduceDateStem() {
            LocalDateTime dt = LocalDateTime.of(2023, 12, 25, 8, 0, 0);
            Optional<String> stem = strategy.proposeStem(Optional.of(dt));
            assertThat(stem).hasValue("2023-12-25_08-00-00");
        }

        @Test
        @DisplayName("should return empty when date is absent")
        void shouldReturnEmpty_whenDateAbsent() {
            assertThat(strategy.proposeStem(Optional.empty())).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FileUtils.sanitiseStem
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FileUtils.sanitiseStem")
    class SanitiseStemTest {

        @Test
        @DisplayName("should strip Windows-forbidden characters")
        void shouldStripWindowsForbiddenChars() {
            String result = FileUtils.sanitiseStem("file<name>:test");
            assertThat(result).doesNotContain("<", ">", ":");
        }

        @Test
        @DisplayName("should replace multiple spaces and underscores")
        void shouldHandleRepeatedSeparators() {
            String result = FileUtils.sanitiseStem("hello   world");
            assertThat(result).doesNotContain("   ");
        }

        @Test
        @DisplayName("should not exceed 240 bytes in UTF-8")
        void shouldTruncateLongNames() {
            String longName = "A".repeat(300);
            String result = FileUtils.sanitiseStem(longName);
            assertThat(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(240);
        }

        @Test
        @DisplayName("should preserve valid characters unchanged")
        void shouldPreserveValidCharacters() {
            String input  = "2024-03-15_Hello_World";
            String result = FileUtils.sanitiseStem(input);
            assertThat(result).isEqualTo(input);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FileUtils.resolveCollisionFreePath
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FileUtils.resolveCollisionFreePath")
    class CollisionFreePathTest {

        @Test
        @DisplayName("should return target path unchanged when no collision exists")
        void shouldReturnOriginalPath_whenNoCollision() {
            Path result = FileUtils.resolveCollisionFreePath(tempDir, "my-photo", "jpg");
            assertThat(result.getFileName().toString()).isEqualTo("my-photo.jpg");
        }

        @Test
        @DisplayName("should append _1 suffix when target already exists")
        void shouldAppendSuffix_whenTargetExists() throws IOException {
            Files.writeString(tempDir.resolve("my-photo.jpg"), "existing");
            Path result = FileUtils.resolveCollisionFreePath(tempDir, "my-photo", "jpg");
            assertThat(result.getFileName().toString()).isEqualTo("my-photo_1.jpg");
        }

        @Test
        @DisplayName("should increment suffix until a free path is found")
        void shouldIncrementSuffix_untilFreePathFound() throws IOException {
            Files.writeString(tempDir.resolve("my-photo.jpg"),   "existing");
            Files.writeString(tempDir.resolve("my-photo_1.jpg"), "existing");
            Path result = FileUtils.resolveCollisionFreePath(tempDir, "my-photo", "jpg");
            assertThat(result.getFileName().toString()).isEqualTo("my-photo_2.jpg");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UndoJournal
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UndoJournal")
    class UndoJournalTest {

        @Test
        @DisplayName("should return empty list when journal does not exist")
        void shouldReturnEmptyList_whenJournalMissing() {
            Path nonExistent = tempDir.resolve("no-journal.ndjson");
            UndoJournal journal = new UndoJournal(nonExistent);
            assertThat(journal.readAll()).isEmpty();
        }

        @Test
        @DisplayName("should record and read back an operation")
        void shouldRecordAndReadBack_operation() throws IOException {
            Path src = tempDir.resolve("src.jpg");
            Path dst = tempDir.resolve("dst.jpg");
            Files.writeString(src, "x");

            Path journalPath = tempDir.resolve("journal.ndjson");
            UndoJournal journal = new UndoJournal(journalPath);

            UndoJournal.Operation op = new UndoJournal.Operation(
                    Instant.now(), src, dst, "DATE_LOCATION", "session-123");
            journal.record(op);

            List<UndoJournal.Operation> all = journal.readAll();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).sessionId()).isEqualTo("session-123");
            assertThat(all.get(0).strategyUsed()).isEqualTo("DATE_LOCATION");
        }

        @Test
        @DisplayName("should undo a rename by moving file back to original path")
        void shouldUndo_byMovingFileBack() throws IOException {
            Path original = tempDir.resolve("original.jpg");
            Path renamed  = tempDir.resolve("2024-01-01_12-00-00.jpg");
            Files.writeString(renamed, "image-data");

            Path journalPath = tempDir.resolve("journal.ndjson");
            UndoJournal journal = new UndoJournal(journalPath);

            journal.record(new UndoJournal.Operation(
                    Instant.now(), original, renamed, "DATE_LOCATION", "session-abc"));

            journal.undoSession("session-abc");

            assertThat(original).exists();
            assertThat(renamed).doesNotExist();
        }

        @Test
        @DisplayName("should return last session id after multiple records")
        void shouldReturnLastSessionId() throws IOException {
            Path journalPath = tempDir.resolve("journal.ndjson");
            UndoJournal journal = new UndoJournal(journalPath);

            Path a = tempDir.resolve("a.jpg"); Files.writeString(a, "x");
            Path b = tempDir.resolve("b.jpg"); Files.writeString(b, "x");
            Path c = tempDir.resolve("c.jpg"); Files.writeString(c, "x");
            Path d = tempDir.resolve("d.jpg");

            journal.record(new UndoJournal.Operation(Instant.now(), a, d, "DATE_LOCATION", "s1"));
            journal.record(new UndoJournal.Operation(Instant.now(), b, d, "DATE_LOCATION", "s1"));
            journal.record(new UndoJournal.Operation(Instant.now(), c, d, "MOVIE_TITLE_YEAR", "s2"));

            assertThat(journal.lastSessionId()).hasValue("s2");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RenameProposal
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RenameProposal")
    class RenameProposalTest {

        @Test
        @DisplayName("should create Approved proposal via factory method")
        void shouldCreateApproved_viaFactory() throws IOException {
            MediaFile file   = imageFile("photo.jpg");
            Path target      = tempDir.resolve("renamed.jpg");
            RenameProposal p = RenameProposal.approved(file, target, "DATE_LOCATION");

            assertThat(p).isInstanceOf(RenameProposal.Approved.class);
            RenameProposal.Approved approved = (RenameProposal.Approved) p;
            assertThat(approved.targetPath()).isEqualTo(target);
            assertThat(approved.strategyUsed()).isEqualTo("DATE_LOCATION");
        }

        @Test
        @DisplayName("should create Skipped proposal via factory method")
        void shouldCreateSkipped_viaFactory() throws IOException {
            MediaFile file   = imageFile("photo.jpg");
            RenameProposal p = RenameProposal.skipped(file, "no strategy matched");

            assertThat(p).isInstanceOf(RenameProposal.Skipped.class);
            assertThat(((RenameProposal.Skipped) p).reason()).isEqualTo("no strategy matched");
        }

        @Test
        @DisplayName("should throw NPE if target path is null in Approved")
        void shouldThrowNPE_whenTargetPathNull() throws IOException {
            MediaFile file = imageFile("photo.jpg");
            assertThatNullPointerException()
                    .isThrownBy(() -> RenameProposal.approved(file, null, "STRATEGY"));
        }
    }
}
