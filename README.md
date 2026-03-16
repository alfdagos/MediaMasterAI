# MediaRenamer AI

> A Java 21 CLI application that intelligently renames media files based on metadata and external databases.

---

## Features

- **Recursive scanning** — traverses directory trees and detects images, videos and audio files
- **Metadata extraction** — reads EXIF data from images, ID3/Vorbis tags from audio, and container metadata from video files
- **Metadata enrichment** — optionally queries external APIs (TMDB, MusicBrainz, Nominatim) to fill missing fields
- **Smart rename strategies** — `DATE_LOCATION`, `MOVIE_TITLE_YEAR`, `ARTIST_TRACK`, `DATE_ONLY`
- **Preview mode** — shows what _would_ be renamed without touching any file
- **Safe rename** — uses atomic moves; collision handling (skip / suffix / overwrite)
- **Undo** — every rename session is journalled to `~/.media-renamer/undo-journal.ndjson`; fully reversible
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

This produces `target/media-renamer.jar` (fat-jar with all dependencies).

### Run

```bash
# Scan a directory and report all media files found
java -jar target/media-renamer.jar scan /path/to/photos

# Preview renames (no files are changed)
java -jar target/media-renamer.jar preview /path/to/photos

# Execute renames
java -jar target/media-renamer.jar rename /path/to/photos

# Undo the last rename session
java -jar target/media-renamer.jar undo
```

### Development run (without packaging)

```bash
mvn compile exec:java -Dexec.mainClass=it.alf.mediarenamer.cli.MediaRenamerCLI \
    -Dexec.args="scan /path/to/photos"
```

---

## Commands

### `scan`

Scans a directory recursively and reports media files found.

```
media-renamer scan [OPTIONS] <directory>

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
media-renamer preview [OPTIONS] <directory>

Options:
  --strategy <id>     DATE_LOCATION | MOVIE_TITLE_YEAR | ARTIST_TRACK | DATE_ONLY (default: auto)
  --depth <n>         Maximum directory depth
  --output <file>     Write proposals to a JSON file
  -h, --help          Show help
```

### `rename`

Executes rename operations after optional confirmation.

```
media-renamer rename [OPTIONS] <directory>

Options:
  --strategy <id>     Rename strategy (default: auto)
  --collision <pol>   SKIP | SUFFIX | OVERWRITE (default: SKIP)
  --depth <n>         Maximum directory depth
  --yes               Skip confirmation prompt
  --output <file>     Write rename report to a JSON file
  -h, --help          Show help
```

### `undo`

Reverts the last rename session (or a specific session by ID).

```
media-renamer undo [OPTIONS]

Options:
  --session <id>      Undo a specific session UUID (default: last session)
  --list              List all recorded sessions
  --yes               Skip confirmation prompt
  -h, --help          Show help
```

---

## Rename Strategies

| Strategy ID | Target | Pattern | Example |
|---|---|---|---|
| `DATE_LOCATION` | Images with EXIF | `YYYY-MM-DD_HH-mm-ss[_City-Country]` | `2024-03-15_14-32-07_Rome-Italy.jpg` |
| `MOVIE_TITLE_YEAR` | Videos with title | `Movie_Title_YYYY` | `The_Grand_Budapest_Hotel_2014.mp4` |
| `ARTIST_TRACK` | Audio with tags | `[NN_]Artist_-_Title` | `01_Pink_Floyd_-_Comfortably_Numb.mp3` |
| `DATE_ONLY` | Any media with date | `YYYY-MM-DD_HH-mm-ss` | `2024-03-15_14-32-07.jpg` |

---

## Configuration

MediaRenamer reads configuration from (highest priority first):

1. CLI flags
2. `~/.media-renamer/config.properties`
3. Built-in defaults

### API Keys (environment variables)

```bash
export TMDB_API_KEY=your_tmdb_key          # The Movie Database
export OPENCAGE_API_KEY=your_opencage_key  # Geolocation (optional; falls back to Nominatim)
```

---

## Project Structure

```
media-renamer-ai/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/media/renamer/
│   │   │   ├── cli/          # Picocli commands
│   │   │   ├── scanner/      # Filesystem traversal
│   │   │   ├── metadata/     # Metadata extractors
│   │   │   ├── enrichment/   # External API enrichment
│   │   │   ├── rename/       # Rename engine & strategies
│   │   │   ├── model/        # Domain records & sealed types
│   │   │   └── util/         # Shared utilities
│   │   └── resources/
│   │       └── logback.xml
│   └── test/
│       └── java/com/media/renamer/
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
