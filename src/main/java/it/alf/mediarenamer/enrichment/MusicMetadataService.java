package it.alf.mediarenamer.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.alf.mediarenamer.cache.MetadataCacheService;
import it.alf.mediarenamer.model.MediaMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Enriches audio file metadata via the MusicBrainz recording search API.
 *
 * <p>The service supports two lookup paths:</p>
 * <ol>
 *   <li>If the raw metadata contains a MusicBrainz track ID (MBID), it performs a
 *       direct {@code /ws/2/recording/{mbid}} lookup for highest accuracy.</li>
 *   <li>Otherwise it falls back to a text search on artist + title.</li>
 * </ol>
 *
 * <p>MusicBrainz does not require an API key but enforces a 1 req/s rate limit
 * per the terms of service. The caller is responsible for throttling.</p>
 */
public class MusicMetadataService {

    private static final Logger log = LoggerFactory.getLogger(MusicMetadataService.class);

    static final String MB_BASE           = "https://musicbrainz.org/ws/2";
    static final String MB_RECORDING_URL  = MB_BASE + "/recording/";
    static final String MB_SEARCH_URL     = MB_BASE + "/recording";
    static final String API_SOURCE        = "MUSICBRAINZ";
    static final long   TTL_SECONDS       = 7 * 86_400L; // 7 days

    private static final String USER_AGENT = "MediaRenamerAI/1.0 (github.com/media-renamer-ai)";

    private final OkHttpClient         httpClient;
    private final ObjectMapper         mapper;
    private final MetadataCacheService cacheService;

    /**
     * Creates a {@code MusicMetadataService} with injected dependencies.
     *
     * @param httpClient   OkHttp client; must not be {@code null}
     * @param mapper       Jackson mapper; must not be {@code null}
     * @param cacheService DB-backed API cache; may be {@code null}
     */
    public MusicMetadataService(OkHttpClient httpClient,
                                 ObjectMapper mapper,
                                 MetadataCacheService cacheService) {
        this.httpClient   = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.mapper       = Objects.requireNonNull(mapper,     "mapper must not be null");
        this.cacheService = cacheService;
    }

    /** Creates a {@code MusicMetadataService} with default HTTP client and no cache. */
    public MusicMetadataService() {
        this(buildDefaultClient(), new ObjectMapper(), null);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Enriches an audio file by querying MusicBrainz.
     *
     * @param audio raw audio metadata; must not be {@code null}
     * @return enriched metadata if a match was found, or empty otherwise
     */
    public Optional<EnrichedMetadata> enrich(MediaMetadata.AudioMetadata audio) {
        Objects.requireNonNull(audio, "audio must not be null");

        // 1. MBID direct lookup
        if (audio.musicBrainzTrackId().isPresent()) {
            String mbid = audio.musicBrainzTrackId().get();
            return lookupByMbid(mbid);
        }

        // 2. Text search
        Optional<String> artist = audio.artist();
        Optional<String> title  = audio.title();
        if (artist.isEmpty() && title.isEmpty()) return Optional.empty();

        String query = buildTextQuery(artist, title);
        return searchByText(query, audio);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Optional<EnrichedMetadata> lookupByMbid(String mbid) {
        String cacheKey = API_SOURCE + "_MBID:" + mbid;
        String body = fromCache(cacheKey).orElseGet(() -> {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(MB_RECORDING_URL + mbid)).newBuilder()
                    .addQueryParameter("fmt", "json")
                    .addQueryParameter("inc", "artist-credits+releases")
                    .build();
            return fetchJson(url, cacheKey);
        });
        return parseRecording(body);
    }

    private Optional<EnrichedMetadata> searchByText(String query, MediaMetadata.AudioMetadata audio) {
        String cacheKey = API_SOURCE + "_TEXT:" + query.toLowerCase();
        String body = fromCache(cacheKey).orElseGet(() -> {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(MB_SEARCH_URL)).newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("fmt", "json")
                    .addQueryParameter("limit", "1")
                    .build();
            return fetchJson(url, cacheKey);
        });
        if (body == null) return Optional.empty();
        try {
            JsonNode root     = mapper.readTree(body);
            JsonNode recs     = root.path("recordings");
            if (!recs.isArray() || recs.isEmpty()) return Optional.empty();
            JsonNode first    = recs.get(0);

            String canonicalTitle  = jsonText(first, "title").orElse(null);
            Optional<String> artist = jsonText(first.path("artist-credit").get(0).path("artist"), "name");
            Optional<String> album  = Optional.empty();

            JsonNode releases = first.path("releases");
            if (releases.isArray() && !releases.isEmpty()) {
                album = jsonText(releases.get(0), "title");
            }

            Map<String, String> extra = new HashMap<>();
            audio.trackNumber().ifPresent(t -> extra.put("trackNumber", String.valueOf(t)));

            return Optional.of(new EnrichedMetadata(
                    Optional.ofNullable(canonicalTitle),
                    Optional.empty(),
                    artist,
                    album,
                    Optional.empty(),
                    Optional.empty(),
                    Collections.unmodifiableMap(extra)));
        } catch (Exception e) {
            log.warn("Failed to parse MusicBrainz search response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<EnrichedMetadata> parseRecording(String body) {
        if (body == null) return Optional.empty();
        try {
            JsonNode root  = mapper.readTree(body);
            String   title = root.path("title").asText(null);
            if (title == null) return Optional.empty();

            Optional<String> artist = Optional.empty();
            JsonNode credits = root.path("artist-credit");
            if (credits.isArray() && !credits.isEmpty()) {
                artist = jsonText(credits.get(0).path("artist"), "name");
            }

            Optional<String> album = Optional.empty();
            JsonNode releases = root.path("releases");
            if (releases.isArray() && !releases.isEmpty()) {
                album = jsonText(releases.get(0), "title");
            }

            return Optional.of(new EnrichedMetadata(
                    Optional.of(title), Optional.empty(),
                    artist, album,
                    Optional.empty(), Optional.empty(), Map.of()));
        } catch (Exception e) {
            log.warn("Failed to parse MusicBrainz recording: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String fetchJson(HttpUrl url, String cacheKey) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String body = response.body().string();
            if (cacheService != null) cacheService.saveApiResponse(cacheKey, body, API_SOURCE, TTL_SECONDS);
            return body;
        } catch (IOException e) {
            log.warn("MusicBrainz request failed: {}", e.getMessage());
            return null;
        }
    }

    private Optional<String> fromCache(String cacheKey) {
        return cacheService != null ? cacheService.findApiResponse(cacheKey) : Optional.empty();
    }

    private String buildTextQuery(Optional<String> artist, Optional<String> title) {
        List<String> parts = new ArrayList<>();
        title.ifPresent(t -> parts.add("recording:" + escape(t)));
        artist.ifPresent(a -> parts.add("artist:" + escape(a)));
        return String.join(" AND ", parts);
    }

    private String escape(String s) { return "\"" + s.replace("\"", "\\\"") + "\""; }

    private Optional<String> jsonText(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isTextual() ? Optional.of(f.asText()) : Optional.empty();
    }

    private static OkHttpClient buildDefaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }
}
