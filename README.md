# MediaMaster AI

> A Java 21 CLI application that intelligently renames media files based on metadata and external databases.

---

## Features

- **Recursive scanning** — traverses directory trees and detects images, videos and audio files
- **Metadata extraction** — reads EXIF data from images, ID3/Vorbis tags from audio, and container metadata from video files
- **Metadata enrichment** — optionally queries external APIs (TMDB, MusicBrainz, Nominatim) to fill missing fields
- **Smart rename strategies** — `DATE_LOCATION`, `MOVIE_TITLE_YEAR`, `ARTIST_TRACK`, `DATE_ONLY`
- **Preview mode** — shows what _would_ be renamed without touching any file
- **Safe rename** — uses atomic moves; collision handling (skip / suffix / overwrite)
- **Undo** — every rename session is journalled to `~/.media-master/undo-journal.ndjson`; fully reversible
- **Duplicate detection** — identifies duplicate files by SHA-256 content hash

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21+ |
| Maven | 3.9+ |

---

## Quick Start

### Build

```bash
mvn clean package -q
```

This produces `target/media-master.jar` (fat-jar with all dependencies).

### Run

```bash
# Scan a directory and report all media files found
java -jar target/media-master.jar scan /path/to/photos

# Preview renames (no files are changed)
java -jar target/media-master.jar preview /path/to/photos

# Execute renames
java -jar target/media-master.jar rename /path/to/photos

# Undo the last rename session
java -jar target/media-master.jar undo
```

### Development run (without packaging)

```bash
mvn compile exec:java -Dexec.mainClass=it.alf.mediamaster.cli.MediaMasterCLI \
    -Dexec.args="scan /path/to/photos"
```

---

## Commands

### `scan`

Scans a directory recursively and reports media files found.

```
media-master scan [OPTIONS] <directory>

Options:
  --depth <n>         Maximum directory depth (default: unlimited)
  --include-hidden    Include hidden files and directories
  --exclude <glob>    Glob pattern to exclude (repeatable)
  --parallel          Enable parallel traversal
  --output <file>     Write JSON scan report to a file
  -h, --help          Show help
```

### `preview`

Generates rename proposals and prints them without touching any file.

```
media-master preview [OPTIONS] <directory>

Options:
  --strategy <id>     DATE_LOCATION | MOVIE_TITLE_YEAR | ARTIST_TRACK | DATE_ONLY (default: auto)
  --depth <n>         Maximum directory depth
  --no-enrich         Disable external API enrichment
  --plex              Use Plex Media Server compatible naming
  --output <file>     Write proposals to a JSON file
  -h, --help          Show help
```

### `rename`

Executes rename operations after optional confirmation.

```
media-master rename [OPTIONS] <directory>

Options:
  --strategy <id>     Rename strategy (default: auto)
  --collision <pol>   SKIP | SUFFIX | OVERWRITE (default: SKIP)
  --depth <n>         Maximum directory depth
  --no-enrich         Disable external API enrichment
  --plex              Use Plex Media Server compatible naming
  --yes               Skip confirmation prompt
  --output <file>     Write rename report to a JSON file
  -h, --help          Show help
```

### `undo`

Reverts the last rename session (or a specific session by ID).

```
media-master undo [OPTIONS]

Options:
  --session <id>      Undo a specific session UUID (default: last session)
  --list              List all recorded sessions
  --yes               Skip confirmation prompt
  -h, --help          Show help
```

---

## Rename Strategies

### Standard mode (default)

| Strategy ID | Target | Pattern | Example |
|---|---|---|---|
| `DATE_LOCATION` | Images with EXIF | `YYYY-MM-DD_HH-mm-ss[_City-Country]` | `2024-03-15_14-32-07_Rome-Italy.jpg` |
| `MOVIE_TITLE_YEAR` | Videos with title | `Movie_Title_YYYY` | `The_Grand_Budapest_Hotel_2014.mp4` |
| `SERIES_SEASON_EPISODE` | TV episodes | `Show_Name_S02E03[_Episode_Title]` | `Breaking_Bad_S02E03_Bit_by_a_Dead_Bee.mkv` |
| `MUSIC_ARTIST_ALBUM_TRACK` | Audio with tags | `NNN_Artist_-_Album_-_Title` | `001_Pink_Floyd_-_The_Wall_-_Comfortably_Numb.mp3` |
| `ARTIST_TRACK` | Audio (legacy) | `[NN_]Artist_-_Title` | `01_Pink_Floyd_-_Comfortably_Numb.mp3` |
| `DATE_ONLY` | Any media with date | `YYYY-MM-DD_HH-mm-ss` | `2024-03-15_14-32-07.jpg` |

### Plex mode (`--plex`)

When `--plex` is specified, filenames follow [Plex Media Server naming conventions](https://support.plex.tv/articles/naming-and-organizing-your-movie-media-files/).
Spaces are preserved, parentheses used for year, episodes use ` - ` as separator.

| Media type | Pattern | Example |
|---|---|---|
| Movie | `Movie Title (Year)` | `The Grand Budapest Hotel (2014).mp4` |
| TV episode | `Show Name - S01E02 - Episode Title` | `Breaking Bad - S02E03 - Bit by a Dead Bee.mkv` |
| TV episode (no title) | `Show Name - S01E02` | `Breaking Bad - S02E03.mkv` |
| Music | `NN - Track Title` | `01 - Comfortably Numb.mp3` |
| Image | _(unchanged from standard)_ | `2024-03-15_14-32-07_Rome-Italy.jpg` |

> **Note:** For Plex music libraries, artist and album are resolved from ID3 tags and folder
> structure by Plex itself; the filename only needs the track number and title.

---

## Configuration

MediaMaster reads configuration from (highest priority first):

1. CLI flags
2. `~/.media-master/config.properties`
3. Built-in defaults

### API Keys (environment variables)

```bash
export TMDB_API_KEY=your_tmdb_key          # The Movie Database
export OPENCAGE_API_KEY=your_opencage_key  # Geolocation (optional; falls back to Nominatim)
```

---

## Project Structure

```
media-master-ai/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/it/alf/mediamaster/
│   │   │   ├── cli/          # Picocli commands
│   │   │   ├── scanner/      # Filesystem traversal
│   │   │   ├── metadata/     # Metadata extractors
│   │   │   ├── enrichment/   # External API enrichment
│   │   │   ├── rename/       # Rename engine & strategies
│   │   │   │   └── strategies/  # Advanced rename strategies (photo, movie, series, music)
│   │   │   ├── series/       # TV series filename parser
│   │   │   ├── duplicate/    # Duplicate detection (SHA-256)
│   │   │   ├── cache/        # SQLite persistence layer
│   │   │   ├── history/      # Rename history & undo
│   │   │   ├── batch/        # End-to-end pipeline orchestration
│   │   │   ├── model/        # Domain records & sealed types
│   │   │   └── util/         # Shared utilities
│   │   └── resources/
│   │       ├── logback.xml
│   │       └── schema.sql
│   └── test/
│       └── java/it/alf/mediamaster/
└── docs/
    └── architecture.md
```

---

## Architecture

See [docs/architecture.md](docs/architecture.md) for a detailed description of the system components,
metadata pipeline, rename engine and future extension points.

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Implement changes following the coding conventions in [.github/copilot-instructions.md](.github/copilot-instructions.md)
4. Run tests: `mvn test`
5. Open a pull request

---

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.
