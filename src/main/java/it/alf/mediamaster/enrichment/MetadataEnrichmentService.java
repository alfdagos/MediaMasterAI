package it.alf.mediamaster.enrichment;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.alf.mediamaster.model.MediaFile;
import it.alf.mediamaster.model.MediaMetadata;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Enriches raw {@link MediaMetadata} with data from external APIs.
 *
 * <p>The service orchestrates three enrichment sources in order:</p>
 * <ol>
 *   <li><b>TMDB</b> — for VIDEO files: resolves canonical movie title and release year</li>
 *   <li><b>MusicBrainz</b> — for AUDIO files: resolves canonical track title and artist</li>
 *   <li><b>Nominatim (OpenStreetMap)</b> — for IMAGE files with GPS: resolves city and country</li>
 * </ol>
 *
 * <p>Responses are cached in-memory for {@value #CACHE_TTL_MINUTES} minutes to avoid
 * hammering rate-limited APIs during batch processing.</p>
 *
 * <p>API keys are read from environment variables. If a key is absent the corresponding
 * enricher is silently skipped.</p>
 */
public class MetadataEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(MetadataEnrichmentService.class);

    private static final int    CACHE_TTL_MINUTES     = 60;
    private static final String TMDB_API_KEY_ENV       = "TMDB_API_KEY";
    private static final String TMDB_BASE_URL          = "https://api.themoviedb.org/3/search/movie";
    private static final String MUSICBRAINZ_BASE_URL   = "https://musicbrainz.org/ws/2/recording/";
    private static final String NOMINATIM_BASE_URL     = "https://nominatim.openstreetmap.org/reverse";
    private static final String USER_AGENT             = "MediaMasterAI/1.0 (github.com/media-master-ai)";

    private final OkHttpClient   httpClient;
    private final ObjectMapper   objectMapper;

    /** Simple time-based cache: key → (value, expiresAt epoch-ms). */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Creates a new enrichment service using the default OkHttpClient configuration.
     */
    public MetadataEnrichmentService() {
        this(buildDefaultClient(), new ObjectMapper());
    }

    /**
     * Creates a new enrichment service with injected dependencies (suitable for testing).
     *
     * @param httpClient   OkHttp client to use for all outbound requests
     * @param objectMapper Jackson mapper for JSON parsing
     */
    public MetadataEnrichmentService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient   = Objects.requireNonNull(httpClient,   "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Attempts to enrich the given metadata using all applicable external sources.
     *
     * <p>Failures from any single source are logged and swallowed — enrichment is
     * best-effort and must never abort the rename pipeline.</p>
     *
     * @param file     the media file being processed
     * @param metadata raw metadata extracted from the file
     * @return an {@link EnrichedMetadata} record; fields absent from all APIs remain empty
     */
    public EnrichedMetadata enrich(MediaFile file, MediaMetadata metadata) {
        Objects.requireNonNull(file,     "file must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");

        return switch (file.type()) {
            case VIDEO -> enrichVideo(file, (MediaMetadata.VideoMetadata) metadata);
            case AUDIO -> enrichAudio(file, (MediaMetadata.AudioMetadata) metadata);
            case IMAGE -> enrichImage(file, (MediaMetadata.ImageMetadata) metadata);
        };
    }

    // ── Video enrichment via TMDB ──────────────────────────────────────────

    private EnrichedMetadata enrichVideo(MediaFile file, MediaMetadata.VideoMetadata meta) {
        String tmdbKey = System.getenv(TMDB_API_KEY_ENV);
        if (tmdbKey == null || tmdbKey.isBlank()) {
            log.debug("TMDB_API_KEY not set; skipping movie enrichment for {}", file.filename());
            return EnrichedMetadata.fromVideo(meta);
        }

        // Use the embedded title or the file stem as the search query
        String query = meta.title()
                .filter(t -> !t.isBlank())
                .orElse(file.stem());

        String cacheKey = "tmdb:" + query.toLowerCase();
        Optional<CacheEntry> cached = getCached(cacheKey);
        if (cached.isPresent()) {
            TmdbSearchResult result = (TmdbSearchResult) cached.get().value();
            return applyTmdbResult(meta, result);
        }

        log.debug("Querying TMDB for: '{}'", query);
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(TMDB_BASE_URL)).newBuilder()
                    .addQueryParameter("query",   query)
                    .addQueryParameter("api_key", tmdbKey)
                    .addQueryParameter("language", "en-US")
                    .addQueryParameter("page", "1")
                    .build();

            String json = executeGet(url.toString());
            TmdbSearchResponse response = objectMapper.readValue(json, TmdbSearchResponse.class);

            if (response.results() != null && !response.results().isEmpty()) {
                TmdbSearchResult top = response.results().get(0);
                putCache(cacheKey, top);
                return applyTmdbResult(meta, top);
            }
        } catch (Exception e) {
            log.warn("TMDB enrichment failed for '{}': {}", query, e.getMessage());
        }
        return EnrichedMetadata.fromVideo(meta);
    }

    private EnrichedMetadata applyTmdbResult(MediaMetadata.VideoMetadata meta, TmdbSearchResult r) {
        Optional<Integer> year = Optional.ofNullable(r.releaseDate())
                .filter(d -> d.length() >= 4)
                .map(d -> {
                    try { return Integer.parseInt(d.substring(0, 4)); }
                    catch (NumberFormatException e) { return null; }
                });
        return new EnrichedMetadata(
                Optional.ofNullable(r.title()).map(String::strip),
                year,
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Map.of());
    }

    // ── Audio enrichment via MusicBrainz ──────────────────────────────────

    private EnrichedMetadata enrichAudio(MediaFile file, MediaMetadata.AudioMetadata meta) {
        // If a MusicBrainz ID is already present, look up the recording directly
        if (meta.musicBrainzTrackId().isPresent()) {
            return enrichAudioByMbid(meta.musicBrainzTrackId().get(), meta);
        }
        // Otherwise search by artist + title
        String artist = meta.artist().orElse("");
        String title  = meta.title().orElse(file.stem());
        if (artist.isBlank() && title.isBlank()) return EnrichedMetadata.fromAudio(meta);
        return enrichAudioBySearch(artist, title, meta);
    }

    private EnrichedMetadata enrichAudioByMbid(String mbid, MediaMetadata.AudioMetadata meta) {
        String cacheKey = "mb:id:" + mbid;
        Optional<CacheEntry> cached = getCached(cacheKey);
        if (cached.isPresent()) {
            return buildAudioEnriched(meta, (MbRecording) cached.get().value());
        }

        String url = MUSICBRAINZ_BASE_URL + mbid + "?fmt=json&inc=artist-credits+releases";
        log.debug("Querying MusicBrainz by MBID: {}", mbid);
        try {
            rateLimitMusicBrainz();
            String json = executeGet(url);
            MbRecording rec = objectMapper.readValue(json, MbRecording.class);
            putCache(cacheKey, rec);
            return buildAudioEnriched(meta, rec);
        } catch (Exception e) {
            log.warn("MusicBrainz MBID lookup failed for '{}': {}", mbid, e.getMessage());
        }
        return EnrichedMetadata.fromAudio(meta);
    }

    private EnrichedMetadata enrichAudioBySearch(String artist, String title,
                                                  MediaMetadata.AudioMetadata meta) {
        String query    = (artist.isBlank() ? "" : "artist:" + artist + " AND ") + "recording:" + title;
        String cacheKey = "mb:search:" + query.toLowerCase();
        Optional<CacheEntry> cached = getCached(cacheKey);
        if (cached.isPresent()) {
            return buildAudioEnriched(meta, (MbRecording) cached.get().value());
        }

        log.debug("Querying MusicBrainz: '{}'", query);
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(MUSICBRAINZ_BASE_URL)).newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("fmt",   "json")
                    .addQueryParameter("limit", "1")
                    .build();
            rateLimitMusicBrainz();
            String json = executeGet(url.toString());
            MbSearchResponse response = objectMapper.readValue(json, MbSearchResponse.class);
            if (response.recordings() != null && !response.recordings().isEmpty()) {
                MbRecording top = response.recordings().get(0);
                putCache(cacheKey, top);
                return buildAudioEnriched(meta, top);
            }
        } catch (Exception e) {
            log.warn("MusicBrainz search failed for artist='{}', title='{}': {}",
                     artist, title, e.getMessage());
        }
        return EnrichedMetadata.fromAudio(meta);
    }

    private EnrichedMetadata buildAudioEnriched(MediaMetadata.AudioMetadata meta, MbRecording rec) {
        Optional<String> canonicalTitle  = Optional.ofNullable(rec.title()).map(String::strip);
        Optional<String> canonicalArtist = Optional.empty();
        if (rec.artistCredit() != null && !rec.artistCredit().isEmpty()) {
            canonicalArtist = Optional.ofNullable(rec.artistCredit().get(0).name()).map(String::strip);
        }
        return new EnrichedMetadata(canonicalTitle, Optional.empty(),
                canonicalArtist, Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of());
    }

    // ── Image enrichment via Nominatim ─────────────────────────────────────

    private EnrichedMetadata enrichImage(MediaFile file, MediaMetadata.ImageMetadata meta) {
        if (!meta.hasGpsCoordinates()) {
            log.debug("No GPS in {}, skipping geo-enrichment", file.filename());
            return EnrichedMetadata.fromImage(meta);
        }
        double lat = meta.gpsLatitude().orElseThrow();
        double lon = meta.gpsLongitude().orElseThrow();

        String cacheKey = String.format("nominatim:%.4f:%.4f", lat, lon);
        Optional<CacheEntry> cached = getCached(cacheKey);
        if (cached.isPresent()) {
            return buildGeoEnriched(meta, (NominatimResponse) cached.get().value());
        }

        log.debug("Querying Nominatim: lat={}, lon={}", lat, lon);
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(NOMINATIM_BASE_URL)).newBuilder()
                    .addQueryParameter("lat",    String.valueOf(lat))
                    .addQueryParameter("lon",    String.valueOf(lon))
                    .addQueryParameter("format", "json")
                    .addQueryParameter("zoom",   "10")
                    .build();
            String json = executeGet(url.toString());
            NominatimResponse response = objectMapper.readValue(json, NominatimResponse.class);
            putCache(cacheKey, response);
            return buildGeoEnriched(meta, response);
        } catch (Exception e) {
            log.warn("Nominatim geo-enrichment failed for lat={}, lon={}: {}", lat, lon, e.getMessage());
        }
        return EnrichedMetadata.fromImage(meta);
    }

    private EnrichedMetadata buildGeoEnriched(MediaMetadata.ImageMetadata meta, NominatimResponse r) {
        Optional<String> city    = Optional.empty();
        Optional<String> country = Optional.empty();
        if (r.address() != null) {
            city    = Optional.ofNullable(
                    r.address().city() != null ? r.address().city() : r.address().town())
                    .map(String::strip);
            country = Optional.ofNullable(r.address().country()).map(String::strip);
        }
        return new EnrichedMetadata(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), city, country, Map.of());
    }

    // ── HTTP execution ─────────────────────────────────────────────────────

    private String executeGet(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for URL: " + url);
            }
            var body = response.body();
            if (body == null) throw new IOException("Empty response body from: " + url);
            return body.string();
        }
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    private volatile long lastMusicBrainzCall = 0L;

    private void rateLimitMusicBrainz() {
        long now  = System.currentTimeMillis();
        long wait = 1_050L - (now - lastMusicBrainzCall); // 1 req/s + 50 ms margin
        if (wait > 0) {
            try {
                log.trace("MusicBrainz rate-limit: waiting {} ms", wait);
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastMusicBrainzCall = System.currentTimeMillis();
    }

    // ── Cache ──────────────────────────────────────────────────────────────

    private record CacheEntry(Object value, long expiresAt) {}

    private Optional<CacheEntry> getCached(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) return Optional.empty();
        if (System.currentTimeMillis() > entry.expiresAt()) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private void putCache(String key, Object value) {
        long ttlMs = TimeUnit.MINUTES.toMillis(CACHE_TTL_MINUTES);
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMs));
    }

    // ── Default HTTP client ────────────────────────────────────────────────

    private static OkHttpClient buildDefaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(15))
                .followRedirects(true)
                .build();
    }

    // ── Jackson DTOs ───────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbSearchResponse(List<TmdbSearchResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbSearchResult(String title,
                            @com.fasterxml.jackson.annotation.JsonProperty("release_date") String releaseDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MbSearchResponse(List<MbRecording> recordings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MbRecording(String title, String id,
                       @com.fasterxml.jackson.annotation.JsonProperty("artist-credit") List<MbArtistCredit> artistCredit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MbArtistCredit(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NominatimResponse(NominatimAddress address) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NominatimAddress(String city, String town, String country,
                            @com.fasterxml.jackson.annotation.JsonProperty("country_code") String countryCode) {}
}
