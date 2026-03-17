package it.alf.mediamaster.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.alf.mediamaster.cache.MetadataCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Enriches movie metadata via the TMDB (The Movie Database) {@code /search/movie} endpoint.
 *
 * <p>The TMDB API key must be provided in the {@code TMDB_API_KEY} environment variable.
 * If the key is absent all queries are skipped and the caller receives an empty result.</p>
 *
 * <p>API responses are stored in the {@link MetadataCacheService} to avoid redundant
 * network calls during batch processing.</p>
 */
public class MovieMetadataService {

    private static final Logger log = LoggerFactory.getLogger(MovieMetadataService.class);

    static final String TMDB_API_KEY_ENV = "TMDB_API_KEY";
    static final String TMDB_BASE_URL    = "https://api.themoviedb.org/3/search/movie";
    static final String API_SOURCE       = "TMDB_MOVIE";
    static final long   TTL_SECONDS      = 86_400L; // 24 h

    private static final String USER_AGENT = "MediaMasterAI/1.0";

    private final OkHttpClient          httpClient;
    private final ObjectMapper          mapper;
    private final MetadataCacheService  cacheService;

    /**
     * Creates a {@code MovieMetadataService} with injected dependencies.
     *
     * @param httpClient   OkHttp client; must not be {@code null}
     * @param mapper       Jackson mapper; must not be {@code null}
     * @param cacheService DB-backed API cache; must not be {@code null}
     */
    public MovieMetadataService(OkHttpClient httpClient,
                                 ObjectMapper mapper,
                                 MetadataCacheService cacheService) {
        this.httpClient   = Objects.requireNonNull(httpClient,    "httpClient must not be null");
        this.mapper       = Objects.requireNonNull(mapper,        "mapper must not be null");
        this.cacheService = Objects.requireNonNull(cacheService,  "cacheService must not be null");
    }

    /** Creates a {@code MovieMetadataService} with default HTTP client and no cache. */
    public MovieMetadataService() {
        this(buildDefaultClient(), new ObjectMapper(), null);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Queries TMDB for a movie matching the given title query.
     *
     * @param query search query (typically the file stem or embedded title)
     * @return enriched metadata if a match was found, or empty otherwise
     */
    public Optional<EnrichedMetadata> search(String query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) return Optional.empty();

        String tmdbKey = System.getenv(TMDB_API_KEY_ENV);
        if (tmdbKey == null || tmdbKey.isBlank()) {
            log.debug("TMDB_API_KEY not set; skipping movie search for '{}'", query);
            return Optional.empty();
        }

        String cacheKey = API_SOURCE + ":" + query.toLowerCase();

        // Check DB cache first
        if (cacheService != null) {
            Optional<String> cached = cacheService.findApiResponse(cacheKey);
            if (cached.isPresent()) {
                return parseAndMap(cached.get());
            }
        }

        // Execute HTTP request
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(TMDB_BASE_URL)).newBuilder()
                .addQueryParameter("api_key", tmdbKey)
                .addQueryParameter("query", query)
                .addQueryParameter("include_adult", "false")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("TMDB returned {} for query '{}'", response.code(), query);
                return Optional.empty();
            }
            String body = response.body().string();
            if (cacheService != null) {
                cacheService.saveApiResponse(cacheKey, body, API_SOURCE, TTL_SECONDS);
            }
            return parseAndMap(body);
        } catch (IOException e) {
            log.warn("TMDB request failed for '{}': {}", query, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Optional<EnrichedMetadata> parseAndMap(String json) {
        try {
            TmdbResponse response = mapper.readValue(json, TmdbResponse.class);
            if (response.results() == null || response.results().isEmpty()) return Optional.empty();

            TmdbMovie movie = response.results().getFirst();
            Optional<Integer> year = Optional.ofNullable(movie.release_date())
                    .filter(d -> d.length() >= 4)
                    .map(d -> {
                        try { return Integer.parseInt(d.substring(0, 4)); }
                        catch (NumberFormatException e) { return null; }
                    });

            return Optional.of(new EnrichedMetadata(
                    Optional.ofNullable(movie.title()),
                    year,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of()));
        } catch (Exception e) {
            log.warn("Failed to parse TMDB response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static OkHttpClient buildDefaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    // ── JSON DTOs ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbResponse(List<TmdbMovie> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbMovie(String title, String release_date) {}
}
