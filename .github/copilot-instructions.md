# GitHub Copilot — Global Project Instructions
# MediaRenamer

## Project Overview

MediaRenamer is a Java 21 CLI application built with Maven.
It scans directories recursively, extracts metadata from media files (images, videos, audio),
enriches metadata via external APIs, and renames files intelligently based on configurable strategies.

---

## Java & Build

- **Java version**: Java 21 (use records, sealed classes, pattern matching, text blocks where appropriate)
- **Build tool**: Apache Maven 3.9+
- **Root package**: `it.alf.mediarenamer`
- **Encoding**: UTF-8 everywhere (`-Dfile.encoding=UTF-8`)
- **Compiler plugin**: `maven-compiler-plugin` with `source` and `target` set to `21`
- Always declare dependency versions in `<dependencyManagement>` or a `<properties>` block
- Use the BOM pattern when integrating third-party library suites

---

## Package Structure

```
it.alf.mediarenamer
├── cli              # CLI entry points and command definitions (Picocli)
├── scanner          # Filesystem scanning and media file detection
├── metadata
│   ├── extractor    # Raw metadata extraction (EXIF, ID3, video)
│   └── enrichment   # External API integrations for metadata enrichment
├── rename           # Rename engine, strategies and collision handling
├── undo             # Undo journal and rollback logic
├── duplicate        # Duplicate file detection
├── config           # Application configuration and user settings
├── model            # Domain model: MediaFile, Metadata, RenameOperation, etc.
└── util             # Shared utilities (path helpers, string formatters, etc.)
```

---

## Architecture Principles

- **SOLID**: Apply Single Responsibility, Open/Closed, Liskov, Interface Segregation, Dependency Inversion
- **Dependency Injection**: Use constructor injection; avoid static singletons
- **Modular design**: Each top-level package must be independently testable
- **Interfaces first**: Define interfaces in `model` or the relevant package; keep implementations separate
- **Strategy pattern**: Use it for rename strategies (`RenameStrategy` interface)
- **Chain of Responsibility**: Use it for metadata enrichment pipeline steps

---

## Coding Style

- Follow standard Java naming conventions (camelCase for methods/variables, PascalCase for classes)
- Prefer `var` for local variables only when the type is obvious from context
- Use Java records for immutable data transfer objects (e.g., `MediaFile`, `RenameProposal`)
- Use sealed interfaces/classes to model closed hierarchies (e.g., `MetadataResult`, `RenameResult`)
- Prefer `Optional<T>` over returning `null`
- Prefer `Stream` API for collection processing
- Keep methods short (max ~30 lines); extract private helpers when needed
- Always use `Path` (not `File`) for filesystem operations
- Use `Files` utility class from `java.nio.file`

---

## Logging

- Use **SLF4J** as the logging facade with **Logback** as the implementation
- Logger declaration: `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`
- Log levels:
  - `TRACE`: detailed internal state
  - `DEBUG`: step-by-step processing info
  - `INFO`: key lifecycle events (scan started, file renamed, etc.)
  - `WARN`: recoverable errors (missing metadata, API rate limits)
  - `ERROR`: fatal or unexpected errors
- Never use `System.out.println` for application logic; use the logger
- CLI user-facing output may use `System.out` or a dedicated `ConsoleOutput` service

---

## Error Handling

- Throw specific checked or unchecked exceptions from the domain (`MediaRenamerException`, `MetadataExtractionException`, etc.)
- Never swallow exceptions silently; always log at minimum at `WARN` level
- Wrap third-party checked exceptions into domain exceptions
- Use `try-with-resources` for all I/O operations
- Validate method arguments with `Objects.requireNonNull` and explicit precondition checks
- Provide meaningful error messages including the file path when an error involves a specific file

---

## Testing

- **JUnit 5** (Jupiter) for all unit tests
- **Mockito** for mocking dependencies
- **AssertJ** for fluent assertions
- Test class naming: `<ClassUnderTest>Test`
- Test method naming: `should<ExpectedBehaviour>_when<Condition>()`
- Aim for ≥ 80% line coverage on core packages (`rename`, `scanner`, `metadata`)
- Use `@TempDir` for filesystem-based tests
- Mock all external HTTP calls; never make real network calls in unit tests

---

## Key Dependencies (reference)

```xml
<!-- Metadata extraction -->
<dependency>
  <groupId>com.drewnoakes</groupId>
  <artifactId>metadata-extractor</artifactId>
  <version>2.19.0</version>
</dependency>

<!-- Audio metadata -->
<dependency>
  <groupId>net.jthink</groupId>
  <artifactId>jaudiotagger</artifactId>
  <version>3.0.1</version>
</dependency>

<!-- CLI framework -->
<dependency>
  <groupId>info.picocli</groupId>
  <artifactId>picocli</artifactId>
  <version>4.7.5</version>
</dependency>

<!-- HTTP client -->
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>okhttp</artifactId>
  <version>4.12.0</version>
</dependency>

<!-- JSON parsing -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.0</version>
</dependency>

<!-- Logging -->
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-api</artifactId>
  <version>2.0.12</version>
</dependency>
<dependency>
  <groupId>ch.qos.logback</groupId>
  <artifactId>logback-classic</artifactId>
  <version>1.5.3</version>
</dependency>
```

---

## Security Guidelines

- Never log file contents or sensitive metadata (GPS coordinates, personal data) at INFO level or above
- Sanitize filenames: strip or replace characters that are invalid on Windows, macOS, and Linux
- Validate paths to prevent path traversal attacks (ensure resolved path stays within the scanned root)
- API keys must be read from environment variables or a local config file; never hardcode them
- Limit HTTP redirects and set explicit timeouts on all HTTP clients

---

## What Copilot Should Prioritise

1. Correctness and safety of file operations (no accidental overwrites)
2. Clear separation of concerns across packages
3. Testability (prefer constructor injection, avoid global state)
4. Idiomatic Java 21 code
5. Meaningful commit-ready code with no TODO stubs unless explicitly requested
