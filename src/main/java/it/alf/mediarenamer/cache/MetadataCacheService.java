package it.alf.mediarenamer.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.alf.mediarenamer.enrichment.EnrichedMetadata;
import it.alf.mediarenamer.model.MediaMetadata;
import it.alf.mediarenamer.model.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Service providing a persistent, database-backed cache for extracted and enriched
 * media file metadata, as well as for external API responses.
 *
 * <p>All cache lookups and writes are keyed by the SHA-256 file hash so that
 * renamed or moved files are correctly resolved without re-extracting metadata.</p>
 *
 * <p>Jackson with {@code @JsonTypeInfo} is used for {@link MediaMetadata} serialisation
 * because it is a sealed interface with multiple implementations.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MetadataCacheService cache = new MetadataCacheService(databaseManager);
 *
 * Optional<MediaMetadata> cached = cache.findRawMetadata(hash);
 * if (cached.isEmpty()) {
 *     MediaMetadata meta = extractor.extract(path);
 *     cache.saveRawMetadata(hash, path, meta, sizeBytes, mediaType);
 * }
 * }</pre>
 */
public class MetadataCacheService {

    private static final Logger log = LoggerFactory.getLogger(MetadataCacheService.class);

    private final DatabaseManager db;
    private final ObjectMapper    mapper;

    /**
     * Creates a {@code MetadataCacheService} backed by the given {@link DatabaseManager}.
     *
     * @param db the database manager; must not be {@code null}
     */
    public MetadataCacheService(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .activateDefaultTyping(
                        JsonMapper.builder().build().getPolymorphicTypeValidator(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY)
                .build();
    }

    // ── media_files + metadata_cache ──────────────────────────────────────

    /**
     * Looks up raw (extracted) metadata for the given file hash.
     *
     * @param fileHash SHA-256 hex digest
     * @return cached metadata, or empty if not yet cached
     */
    public Optional<MediaMetadata> findRawMetadata(String fileHash) {
        Objects.requireNonNull(fileHash, "fileHash must not be null");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT raw_json FROM metadata_cache WHERE file_hash = ?")) {
            ps.setString(1, fileHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapper.readValue(rs.getString(1), MediaMetadata.class));
                }
            }
        } catch (Exception e) {
            log.warn("Cache read failed for hash {}: {}", fileHash, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persists raw metadata for a file.
     *
     * <p>Also inserts or replaces the row in {@code media_files} so that the
     * foreign-key constraint in {@code metadata_cache} is satisfied.</p>
     *
     * @param fileHash  SHA-256 hex digest
     * @param filePath  absolute path (informational; may change when file is renamed)
     * @param metadata  extracted metadata object
     * @param sizeBytes file size in bytes
     * @param mediaType media type
     */
    public void saveRawMetadata(String fileHash, String filePath,
                                 MediaMetadata metadata, long sizeBytes, MediaType mediaType) {
        Objects.requireNonNull(fileHash,  "fileHash must not be null");
        Objects.requireNonNull(filePath,  "filePath must not be null");
        Objects.requireNonNull(metadata,  "metadata must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            upsertMediaFile(conn, fileHash, filePath, sizeBytes, mediaType);
            upsertRawJson(conn, fileHash, metadata);
            conn.commit();
            log.debug("Cached raw metadata for hash {}", fileHash);
        } catch (Exception e) {
            log.warn("Cache write failed for hash {}: {}", fileHash, e.getMessage());
        }
    }

    // ── Enriched metadata ─────────────────────────────────────────────────

    /**
     * Looks up enriched metadata for the given file hash.
     *
     * @param fileHash SHA-256 hex digest
     * @return enriched metadata, or empty if not yet cached
     */
    public Optional<EnrichedMetadata> findEnrichedMetadata(String fileHash) {
        Objects.requireNonNull(fileHash, "fileHash must not be null");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT enriched_json FROM metadata_cache WHERE file_hash = ? AND enriched_json IS NOT NULL")) {
            ps.setString(1, fileHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapper.readValue(rs.getString(1), EnrichedMetadata.class));
                }
            }
        } catch (Exception e) {
            log.warn("Enriched cache read failed for hash {}: {}", fileHash, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persists enriched metadata for a file.
     *
     * @param fileHash fileHash SHA-256 hex digest (must already exist in {@code media_files})
     * @param enriched enriched metadata to store
     */
    public void saveEnrichedMetadata(String fileHash, EnrichedMetadata enriched) {
        Objects.requireNonNull(fileHash, "fileHash must not be null");
        Objects.requireNonNull(enriched, "enriched must not be null");
        String sql = """
                UPDATE metadata_cache
                   SET enriched_json = ?, enriched_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')
                 WHERE file_hash = ?
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mapper.writeValueAsString(enriched));
            ps.setString(2, fileHash);
            ps.executeUpdate();
            log.debug("Cached enriched metadata for hash {}", fileHash);
        } catch (Exception e) {
            log.warn("Enriched cache write failed for hash {}: {}", fileHash, e.getMessage());
        }
    }

    // ── API response cache ────────────────────────────────────────────────

    /**
     * Looks up a cached external API response by its cache key.
     *
     * @param cacheKey composite key (typically: source + ":" + query)
     * @return raw JSON response, or empty if not cached or expired
     */
    public Optional<String> findApiResponse(String cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        String sql = """
                SELECT response_json FROM api_cache
                 WHERE cache_key = ?
                   AND (expires_at IS NULL OR expires_at > strftime('%Y-%m-%dT%H:%M:%SZ','now'))
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString(1));
            }
        } catch (Exception e) {
            log.warn("API cache read failed for key {}: {}", cacheKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persists an external API response.
     *
     * @param cacheKey    composite key
     * @param responseJson raw JSON response body
     * @param apiSource   source identifier (e.g. "TMDB", "MUSICBRAINZ")
     * @param ttlSeconds  TTL; 0 or negative means never expire
     */
    public void saveApiResponse(String cacheKey, String responseJson,
                                 String apiSource, long ttlSeconds) {
        Objects.requireNonNull(cacheKey,     "cacheKey must not be null");
        Objects.requireNonNull(responseJson, "responseJson must not be null");
        Objects.requireNonNull(apiSource,    "apiSource must not be null");
        String sql = """
                INSERT INTO api_cache(cache_key, response_json, api_source, expires_at)
                VALUES (?, ?, ?,
                    CASE WHEN ? > 0
                         THEN strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '+' || ? || ' seconds')
                         ELSE NULL END)
                ON CONFLICT(cache_key) DO UPDATE
                   SET response_json = excluded.response_json,
                       cached_at     = strftime('%Y-%m-%dT%H:%M:%SZ','now'),
                       expires_at    = excluded.expires_at
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ps.setString(2, responseJson);
            ps.setString(3, apiSource);
            ps.setLong(4, ttlSeconds);
            ps.setLong(5, ttlSeconds);
            ps.executeUpdate();
            log.debug("Cached API response for key {} (ttl={}s)", cacheKey, ttlSeconds);
        } catch (Exception e) {
            log.warn("API cache write failed for key {}: {}", cacheKey, e.getMessage());
        }
    }

    // ── Management ────────────────────────────────────────────────────────

    /**
     * Clears all rows from all cache tables.
     *
     * <p>Does not drop the tables themselves; the schema remains intact.</p>
     */
    public void clearAll() {
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);
            st.execute("DELETE FROM api_cache");
            st.execute("DELETE FROM metadata_cache");
            st.execute("DELETE FROM media_files");
            conn.commit();
            log.info("All cache tables cleared");
        } catch (SQLException e) {
            log.error("Failed to clear cache tables", e);
            throw new DatabaseManager.DatabaseException("Failed to clear cache tables", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void upsertMediaFile(Connection conn, String hash, String path,
                                  long sizeBytes, MediaType type) throws SQLException {
        String sql = """
                INSERT INTO media_files(file_hash, file_path, file_size, media_type)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(file_hash) DO UPDATE SET file_path = excluded.file_path
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, path);
            ps.setLong(3, sizeBytes);
            ps.setString(4, type.name());
            ps.executeUpdate();
        }
    }

    private void upsertRawJson(Connection conn, String hash, MediaMetadata metadata)
            throws Exception {
        String sql = """
                INSERT INTO metadata_cache(file_hash, raw_json)
                VALUES (?, ?)
                ON CONFLICT(file_hash) DO UPDATE SET raw_json = excluded.raw_json,
                    extracted_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, mapper.writeValueAsString(metadata));
            ps.executeUpdate();
        }
    }
}
