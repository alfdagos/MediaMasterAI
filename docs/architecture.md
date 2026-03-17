# MediaMaster — Architecture Documentation

> Version 2.0 — Updated after Phase 3 feature additions

---

## Table of Contents

- [MediaMaster — Architecture Documentation](#mediamaster--architecture-documentation)
  - [Table of Contents](#table-of-contents)
  - [1. Overview](#1-overview)
  - [2. Package Structure](#2-package-structure)
  - [3. High-Level Architecture](#3-high-level-architecture)
  - [4. Component Details](#4-component-details)
    - [4.1 CLI Layer](#41-cli-layer)
    - [4.2 Batch Processing Pipeline](#42-batch-processing-pipeline)
    - [4.3 Scanner](#43-scanner)
    - [4.4 Metadata Subsystem](#44-metadata-subsystem)
    - [4.5 Enrichment Services](#45-enrichment-services)
    - [4.6 Rename Engine \& Strategies](#46-rename-engine--strategies)
    - [4.7 Duplicate Detection](#47-duplicate-detection)
    - [4.8 TV Series Detection](#48-tv-series-detection)
    - [4.9 Cache Subsystem](#49-cache-subsystem)
    - [4.10 Rename History](#410-rename-history)
    - [4.11 Undo Journal](#411-undo-journal)
  - [5. Database Schema](#5-database-schema)
  - [6. Data-Flow Diagrams](#6-data-flow-diagrams)
    - [Single-file rename pipeline](#single-file-rename-pipeline)
  - [7. Configuration \& Environment](#7-configuration--environment)
  - [8. Security Considerations](#8-security-considerations)
  - [9. Extension Points](#9-extension-points)

---

## 1. Overview

MediaMaster is a Java 21 CLI application that renames media files (images, videos, audio)
intelligently by combining:

- **Local metadata extraction** — EXIF from images, ID3 tags from audio, container metadata from video.
- **External API enrichment** — TMDB for movies and TV series, MusicBrainz for recordings,
  Nominatim/OpenStreetMap for GPS reverse-geocoding.
- **Persistent SQLite cache** — avoids redundant I/O and network calls across runs.
- **Rename strategies** — configurable naming patterns per media type.
- **Duplicate detection** — SHA-256 content fingerprinting.
- **Transactional undo** — every session can be fully rolled back via DB or NDJSON journal.

---

## 2. Package Structure

```
it.alf.mediamaster
├── cli/              # Picocli CLI entry point (subcommands)
│   ├── MediaMasterCLI.java        # Root command + all subcommands
│   └── ConsoleOutput.java          # ANSI-aware console helpers
│
├── scanner/          # Filesystem scanning
│   ├── MediaScanner.java           # Recursive walkFileTree scanner
│   ├── ScanOptions.java            # Depth, hidden-file, symlink options
│   └── ScanResult.java             # Immutable scan summary
│
├── metadata/         # Raw metadata extraction
│   ├── ImageMetadataExtractor.java # EXIF via metadata-extractor
│   ├── AudioMetadataExtractor.java # ID3/Vorbis via jaudiotagger
│   ├── VideoMetadataExtractor.java # Container metadata
│   └── MetadataExtractionException.java
│
├── enrichment/       # External API integrations
│   ├── MetadataEnrichmentService.java  # Legacy orchestrator (in-memory cache)
│   ├── EnrichedMetadata.java           # Unified enrichment record
│   ├── MovieMetadataService.java       # TMDB /search/movie
│   ├── SeriesMetadataService.java      # TMDB /search/tv + episode lookup
│   └── MusicMetadataService.java       # MusicBrainz recording search
│
├── rename/           # Rename engine and core strategies
│   ├── RenameStrategy.java             # Strategy interface
│   ├── SmartRenameEngine.java          # Orchestrates proposal + execution
│   ├── RenameProposal.java             # Sealed: Approved | Skipped
│   ├── RenameResult.java               # Sealed: Success | DryRun | Skipped | Failed
│   ├── RenameOptions.java              # strategyId, collisionPolicy, etc.
│   ├── UndoJournal.java                # NDJSON undo log
│   ├── DateLocationStrategy.java       # YYYY-MM-DD_HH-mm-ss[_City-Country]
│   ├── MovieTitleYearStrategy.java     # Movie_Title_YYYY
│   ├── ArtistTrackStrategy.java        # Artist_-_Title
│   ├── ArtistTrackNumberedStrategy.java# NNN_Artist_-_Title
│   ├── DateOnlyStrategy.java           # Fallback date-only
│   └── strategies/                     # Advanced pipeline strategies
│       ├── PhotoRenameStrategy.java    # PHOTO_DATE_LOCATION
│       ├── MovieRenameStrategy.java    # MOVIE_TITLE_YEAR
│       ├── SeriesRenameStrategy.java   # SERIES_SEASON_EPISODE
│       └── MusicRenameStrategy.java    # MUSIC_ARTIST_ALBUM_TRACK
│
├── series/           # TV series filename parsing
│   └── SeriesParser.java               # Regex SnnEnn and NxNN extraction
│
├── duplicate/        # Content-based duplicate detection
│   └── DuplicateDetector.java          # SHA-256 + size pre-filter → DuplicateGroup
│
├── cache/            # SQLite persistence layer
│   ├── DatabaseManager.java            # JDBC connection + schema auto-init (WAL mode)
│   └── MetadataCacheService.java       # Raw/enriched metadata + API response cache
│
├── history/          # Rename operation history
│   ├── RenameOperation.java            # Immutable history record
│   └── RenameHistoryService.java       # DB-backed session recording + undo
│
├── batch/            # End-to-end pipeline orchestration
│   └── BatchRenameProcessor.java       # Scan → dedupe → propose → execute → persist
│
├── model/            # Domain model
│   ├── MediaFile.java                  # record: path, type, sizeBytes, lastModified
│   ├── MediaType.java                  # enum: IMAGE | VIDEO | AUDIO
│   └── MediaMetadata.java              # Sealed: ImageMetadata | AudioMetadata | VideoMetadata
│
└── util/             # Shared utilities
    └── FileUtils.java                  # sanitiseStem, sha256, resolveCollisionFreePath
```

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                     CLI (Picocli)                                   │
│  scan │ preview │ rename │ undo │ duplicates │ cache-clear          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    BatchRenameProcessor                              │
│                                                                     │
│  ┌──────────┐   ┌────────────────┐   ┌──────────────────────────┐  │
│  │ Scanner  │──▶│DuplicateDetect.│──▶│   SmartRenameEngine      │  │
│  └──────────┘   └────────────────┘   │  extractors + enrichers  │  │
│                                      │  + rename strategies     │  │
│                                      └──────────┬───────────────┘  │
│                                                 │                   │
│  ┌──────────────────┐   ┌──────────────────┐    │                   │
│  │  RenameHistory   │◀──│ MetadataCache    │◀───┘                   │
│  │  (SQLite)        │   │ (SQLite)         │                        │
│  └──────────────────┘   └──────────────────┘                        │
└─────────────────────────────────────────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
  ┌──────────────┐  ┌──────────┐  ┌──────────────────┐
  │ TMDB API     │  │MusicBrainz│  │Nominatim / OSM   │
  │ (movies, TV) │  │(audio)   │  │(GPS → city)      │
  └──────────────┘  └──────────┘  └──────────────────┘
```

---

## 4. Component Details

### 4.1 CLI Layer

`MediaMasterCLI` is the Picocli root command with six subcommands:

| Subcommand      | Purpose                                                         |
|-----------------|-----------------------------------------------------------------|
| `scan`          | Discover and count media files; prints a summary               |
| `preview`       | Generate and display rename proposals without changing files   |
| `rename`        | Execute renames with confirmation prompt and undo journalling  |
| `undo`          | Revert the last (or a specified) rename session                |
| `duplicates`    | Scan and report groups of content-identical files              |
| `cache-clear`   | Wipe all rows from all SQLite cache tables                     |

Exit codes: `0` success · `1` partial failure · `2` fatal/configuration error.

### 4.2 Batch Processing Pipeline

`BatchRenameProcessor` executes the five-stage pipeline:

```
1. Scan           (MediaScanner)
2. Deduplicate    (DuplicateDetector)          ← optional
3. Propose        (SmartRenameEngine.propose)
4. Execute        (SmartRenameEngine.execute)  ← skipped in dry-run
5. Record history (RenameHistoryService)       ← skipped in dry-run
```

`BatchOptions` bundles `ScanOptions`, `RenameOptions`, `detectDuplicates`, and `dryRun`.
`BatchResult` provides per-type counts (success / dryRun / skipped / failed) and a human-readable `summary()`.

### 4.3 Scanner

`MediaScanner` uses `Files.walkFileTree` to recursively discover media files.
`ScanOptions` controls maximum depth, hidden-file inclusion, and symlink following.
Media type is detected by `FileUtils.detectMediaType` (extension-based lookup).

### 4.4 Metadata Subsystem

Three extractors handle different media types:

| Extractor                    | Library              | Formats                             |
|------------------------------|----------------------|-------------------------------------|
| `ImageMetadataExtractor`     | metadata-extractor   | JPEG, PNG, HEIC, TIFF, WebP, RAW …|
| `AudioMetadataExtractor`     | jaudiotagger         | MP3, FLAC, M4A, OGG, WAV …        |
| `VideoMetadataExtractor`     | NIO + heuristics     | MP4, MKV, AVI, MOV …              |

All return a `MediaMetadata` sealed interface variant (`ImageMetadata` / `AudioMetadata` /
`VideoMetadata`). Pattern-matching switch is the idiomatic way to dispatch on type.

### 4.5 Enrichment Services

| Service                    | API                    | Input                   | Output                        |
|----------------------------|------------------------|-------------------------|-------------------------------|
| `MovieMetadataService`     | TMDB `/search/movie`   | Title query             | canonicalTitle, releaseYear   |
| `SeriesMetadataService`    | TMDB `/search/tv` + ep | SeriesInfo              | showName, episodeTitle        |
| `MusicMetadataService`     | MusicBrainz            | MBID or artist+title    | canonicalTitle, artist, album |
| `MetadataEnrichmentService`| TMDB + MB + Nominatim  | MediaFile + raw meta    | All fields (orchestrator)     |

All services use OkHttp with 10 s connect / 20 s read timeouts and `followRedirects(true)`.
API keys are read from environment variables; absent keys cause the enricher to be silently skipped.
All SQL uses `PreparedStatement` (no string concatenation).

### 4.6 Rename Engine & Strategies

`SmartRenameEngine` coordinates the rename pipeline:

1. Extracts raw metadata
2. Enriches (if enabled)
3. Iterates strategies in priority order — first non-empty stem wins
4. Sanitises the stem via `FileUtils.sanitiseStem`
5. Resolves filename collisions per `RenameOptions.CollisionPolicy`

**Strategy priority order (default)**:

```
PhotoRenameStrategy    (IMAGE   → YYYY-MM-DD_HH-mm-ss[_City-Country])
MovieRenameStrategy    (VIDEO   → Movie_Title_YYYY)
SeriesRenameStrategy   (VIDEO   → Show_Name_S02E03[_Episode_Title])
MusicRenameStrategy    (AUDIO   → NNN_Artist_-_Album_-_Title)
DateLocationStrategy   (IMAGE   → YYYY-MM-DD_HH-mm-ss[_City-Country])
MovieTitleYearStrategy (VIDEO   → Movie_Title_YYYY)
ArtistTrackNumbered    (AUDIO   → NNN_Artist_-_Title)
DateOnlyStrategy       (any    → YYYY-MM-DD fallback)
```

### 4.7 Duplicate Detection

`DuplicateDetector` uses two-pass identification:

1. **Pre-filter by size** — files with unique byte counts are eliminated before hashing.
2. **SHA-256 grouping** — remaining files are hashed; identical digests form a `DuplicateGroup`.

`DuplicateGroup.original()` identifies the presumed original by oldest `lastModified` timestamp.
`DuplicateGroup.duplicateFiles()` returns the remaining copies.

### 4.8 TV Series Detection

`SeriesParser` applies two regex patterns (case-insensitive):

| Pattern   | Example input                         | Parsed output               |
|-----------|---------------------------------------|-----------------------------|
| `SnnEnn`  | `Breaking.Bad.S02E03.1080p.mkv`       | show=Breaking Bad, S=2, E=3 |
| `NxNN`    | `The.Office.US.3x08.Part.1.mkv`       | show=The Office US, S=3, E=8|

`buildStem(SeriesInfo)` outputs: `Show_Name_S02E03` or `Show_Name_S02E03_Episode_Title`.

### 4.9 Cache Subsystem

`DatabaseManager` manages the SQLite database at `~/.media-master/media-master.db`.
WAL mode and foreign key enforcement are enabled on every connection via `PRAGMA` statements.
The schema is applied automatically from the embedded `schema.sql` classpath resource.

`MetadataCacheService` provides CRUD over four tables:

| Table            | Key           | Stores                                      |
|------------------|---------------|---------------------------------------------|
| `media_files`    | SHA-256 hash  | path, size, type                            |
| `metadata_cache` | SHA-256 hash  | raw JSON + enriched JSON                    |
| `api_cache`      | source:query  | response JSON with optional TTL expiry      |
| `rename_history` | auto-id       | per-operation rename record (see below)     |

### 4.10 Rename History

`RenameHistoryService` records every rename atomically in `rename_history`.
`RenameOperation` is an immutable record: `sessionId`, `fileHash`, `originalPath`,
`renamedPath`, `strategyUsed`, `renamedAt`, `undoneAt`.

`undoSession(sessionId)` moves files back in reverse insertion order and marks each row
with an `undone_at` timestamp. Filesystem moves use `StandardCopyOption.ATOMIC_MOVE`.

### 4.11 Undo Journal

`UndoJournal` provides a lightweight NDJSON log at `~/.media-master/undo-journal.ndjson`
as a complementary undo path that works without a running database. Used directly by
`SmartRenameEngine` and the `undo` CLI command.

---

## 5. Database Schema

```sql
-- Unique content fingerprints
media_files (id, file_hash UNIQUE, file_path, file_size, media_type, created_at)

-- Extracted + enriched metadata JSON
metadata_cache (id, file_hash UNIQUE FK → media_files,
                raw_json, enriched_json, extracted_at, enriched_at)

-- Rename operations grouped by session UUID
rename_history (id, session_id, file_hash FK → media_files,
                original_path, renamed_path, strategy_used, renamed_at, undone_at)

-- External API response cache with optional TTL
api_cache (id, cache_key UNIQUE, response_json, api_source, cached_at, expires_at)
```

Full DDL: [`src/main/resources/schema.sql`](../src/main/resources/schema.sql)

Indexes exist on: `file_hash`, `file_path`, `session_id`, `cache_key`, `expires_at`.

---

## 6. Data-Flow Diagrams

### Single-file rename pipeline

```
MediaFile
    │
    ├──▶ [Extractor] ──▶ MediaMetadata
    │         │
    │         ▼
    │    MetadataCacheService (read) ──▶ cache hit → return EnrichedMetadata
    │         │ miss
    │         ▼
    │    [EnrichmentService] ──▶ HTTP API ──▶ EnrichedMetadata
    │         │
    │         ▼
    │    MetadataCacheService (write enriched_json)
    │
    ├──▶ SeriesParser (VIDEO only) ──▶ SeriesInfo
    │
    ▼
RenameStrategy.proposeStem(file, enriched)
    │
    ▼
FileUtils.sanitiseStem + resolveCollisionFreePath
    │
    ▼
RenameProposal.Approved(file, targetPath, strategyUsed)
    │
    ▼
Files.move (ATOMIC_MOVE or copy+delete fallback)
    │
    ├──▶ UndoJournal.record(operation)          [NDJSON]
    └──▶ RenameHistoryService.record(operation) [SQLite]
```

---

## 7. Configuration & Environment

| Environment variable | Required | Description                          |
|----------------------|----------|--------------------------------------|
| `TMDB_API_KEY`       | No       | Enables movie and TV series enrichment |

Database path: `~/.media-master/media-master.db` (auto-created by `DatabaseManager`).
Undo journal: `~/.media-master/undo-journal.ndjson` (auto-created by `UndoJournal`).
Log file: `~/.media-master/media-master.log` (rolling, Logback-managed).

---

## 8. Security Considerations

| Concern                     | Mitigation                                                                      |
|-----------------------------|---------------------------------------------------------------------------------|
| SQL injection (OWASP A03)   | All queries use `PreparedStatement`; no string concatenation in SQL             |
| Path traversal (OWASP A01)  | `FileUtils.requireUnderRoot` validates every target path before file operations |
| Filename injection          | `FileUtils.sanitiseStem` strips invalid characters for Win/macOS/Linux          |
| Credential exposure         | API keys read from env vars only; never logged or stored in DB                  |
| Sensitive metadata logging  | GPS coordinates and personal data never logged above TRACE level                |
| Uncontrolled redirects      | OkHttp uses `followRedirects(true)` with explicit timeouts; no unlimited loops  |
| Symlink attacks             | Symlink following is opt-in (`--follow-links`); disabled by default             |

---

## 9. Extension Points

| Extension              | How to add it                                                             |
|------------------------|---------------------------------------------------------------------------|
| New rename strategy    | Implement `RenameStrategy`, register in `SmartRenameEngine`               |
| New metadata extractor | Implement extractor, permit new `MediaMetadata` subtype                   |
| New enrichment API     | Add a service in `enrichment/`, call from `MetadataEnrichmentService`     |
| New media type         | Add to `MediaType` enum, extend `FileUtils.detectMediaType`               |
| New CLI subcommand     | Inner class implementing `Callable<Integer>`, add to `@Command(subcommands=…)` |
| New DB table           | Add DDL to `schema.sql`; `DatabaseManager` will apply it on next startup  |

