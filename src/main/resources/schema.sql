-- MediaRenamer SQLite schema
-- Applied automatically by DatabaseManager on first run.
-- All tables use IF NOT EXISTS for idempotent initialisation.

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- ── media_files ────────────────────────────────────────────────────────────
-- One row per unique file, keyed by SHA-256 hash.
CREATE TABLE IF NOT EXISTS media_files (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    file_hash   TEXT    NOT NULL UNIQUE,
    file_path   TEXT    NOT NULL,
    file_size   INTEGER NOT NULL,
    media_type  TEXT    NOT NULL,          -- IMAGE | AUDIO | VIDEO
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_media_files_hash ON media_files(file_hash);
CREATE INDEX IF NOT EXISTS idx_media_files_path ON media_files(file_path);

-- ── metadata_cache ─────────────────────────────────────────────────────────
-- Stores serialised raw and enriched metadata JSON keyed by file hash.
CREATE TABLE IF NOT EXISTS metadata_cache (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    file_hash       TEXT    NOT NULL UNIQUE,
    raw_json        TEXT    NOT NULL,
    enriched_json   TEXT,
    extracted_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
    enriched_at     TEXT,
    FOREIGN KEY (file_hash) REFERENCES media_files(file_hash) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_metadata_cache_hash ON metadata_cache(file_hash);

-- ── rename_history ─────────────────────────────────────────────────────────
-- Records every rename operation; supports session-level undo.
CREATE TABLE IF NOT EXISTS rename_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT    NOT NULL,
    file_hash       TEXT    NOT NULL,
    original_path   TEXT    NOT NULL,
    renamed_path    TEXT    NOT NULL,
    strategy_used   TEXT    NOT NULL,
    renamed_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
    undone_at       TEXT,                  -- NULL means not yet undone
    FOREIGN KEY (file_hash) REFERENCES media_files(file_hash) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_rename_history_session ON rename_history(session_id);
CREATE INDEX IF NOT EXISTS idx_rename_history_hash    ON rename_history(file_hash);

-- ── api_cache ──────────────────────────────────────────────────────────────
-- Generic key-value store for external API responses.
CREATE TABLE IF NOT EXISTS api_cache (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    cache_key       TEXT    NOT NULL UNIQUE,
    response_json   TEXT    NOT NULL,
    api_source      TEXT    NOT NULL,      -- TMDB | MUSICBRAINZ | NOMINATIM | ...
    cached_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
    expires_at      TEXT                   -- NULL means never expires
);

CREATE INDEX IF NOT EXISTS idx_api_cache_key        ON api_cache(cache_key);
CREATE INDEX IF NOT EXISTS idx_api_cache_expires_at ON api_cache(expires_at);
