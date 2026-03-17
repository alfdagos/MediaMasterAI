package it.alf.mediamaster.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.alf.mediamaster.cache.MetadataCacheService;
import it.alf.mediamaster.series.SeriesParser;
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
 * Enriches TV-series episode metadata via the TMDB {@code /search/tv} and
 * {@code /tv/{id}/season/{s}/episode/{e}} endpoints.
 *
 * <p>The TMDB API key must be provided in the {@code TMDB_API_KEY} environment variable.</p>
 *
 * <p>For each file, the service:</p>
 * <ol>
 *   <li>Searches for the show by name</li>
 *   <li>Resolves the specific episode to retrieve the canonical episode title</li>
 * </ol>
 */
public class SeriesMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SeriesMetadataService.class);

    static final String TMDB_API_KEY_ENV    = "TMDB_API_KEY";
    static final String TMDB_SEARCH_URL     = "https://api.themoviedb.org/3/search/tv";
    static final String TMDB_EPISODE_URL    = "https://api.themoviedb.org/3/tv/%d/season/%d/episode/%d";
    static final String API_SOURCE          = "TMDB_SERIES";
    static final long   TTL_SECONDS         = 86_400L;

    private static final String USER_AGENT = "MediaMasterAI/1.0";

    private final OkHttpClient         httpClient;
    private final ObjectMapper         mapper;
    private final MetadataCacheService cacheService;

    /**
     * Creates a {@code SeriesMetadataService} with injected dependencies.
     *
     * @param httpClient   OkHttp client; must not be {@code null}
     * @param mapper       Jackson mapper; must not be {@code null}
     * @param cacheService DB-backed API cache; may be {@code null} (caching disabled)
     */
    public SeriesMetadataService(OkHttpClient httpClient,
                                  ObjectMapper mapper,
                                  MetadataCacheService cacheService) {
        this.httpClient   = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.mapper       = Objects.requireNonNull(mapper,     "mapper must not be null");
        this.cacheService = cacheService;
    }

    /** Creates a {@code SeriesMetadataService} with default HTTP client and no cache. */
    public SeriesMetadataService() {
        this(buildDefaultClient(), new ObjectMapper(), null);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Enriches metadata for a TV-series episode.
     *
     * @param seriesInfo parsed series info from the filename
     * @return enriched metadata containing canonical show name and episode title, or empty
     */
    public Optional<EnrichedMetadata> enrich(SeriesParser.SeriesInfo seriesInfo) {
        Objects.requireNonNull(seriesInfo, "seriesInfo must not be null");

        String tmdbKey = System.getenv(TMDB_API_KEY_ENV);
        if (tmdbKey == null || tmdbKey.isBlank()) {
            log.debug("TMDB_API_KEY not set; skipping series enrichment for '{}'", seriesInfo.showName());
            return Optional.empty();
        }

        Optional<Integer> showId = findShowId(seriesInfo.showName(), tmdbKey);
        if (showId.isEmpty()) return Optional.empty();

        return fetchEpisode(showId.get(), seriesInfo.season(), seriesInfo.episode(), tmdbKey)
                .map(episode -> buildEnrichedMetadata(seriesInfo, episode));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Optional<Integer> findShowId(String showName, String apiKey) {
        String cacheKey = API_SOURCE + "_SHOW:" + showName.toLowerCase();
        Optional<String> cached = fromCache(cacheKey);
        String body = cached.orElseGet(() -> {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(TMDB_SEARCH_URL)).newBuilder()
                    .addQueryParameter("api_key", apiKey)
                    .addQueryParameter("query", showName)
                    .build();
            return fetchJson(url, cacheKey);
        });
        if (body == null) return Optional.empty();
        try {
            TmdbTvSearchResponse response = mapper.readValue(body, TmdbTvSearchResponse.class);
            if (response.results() == null || response.results().isEmpty()) return Optional.empty();
            return Optional.of(response.results().getFirst().id());
        } catch (Exception e) {
            log.warn("Failed to parse TV search response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TmdbEpisode> fetchEpisode(int showId, int season, int episode, String apiKey) {
        String cacheKey = API_SOURCE + "_EP:" + showId + ":" + season + ":" + episode;
        Optional<String> cached = fromCache(cacheKey);
        String body = cached.orElseGet(() -> {
            String urlStr = TMDB_EPISODE_URL.formatted(showId, season, episode);
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(urlStr)).newBuilder()
                    .addQueryParameter("api_key", apiKey)
                    .build();
            return fetchJson(url, cacheKey);
        });
        if (body == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(body, TmdbEpisode.class));
        } catch (Exception e) {
            log.warn("Failed to parse episode response: {}", e.getMessage());
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
            log.warn("TMDB request failed ({}): {}", url, e.getMessage());
            return null;
        }
    }

    private Optional<String> fromCache(String cacheKey) {
        return cacheService != null ? cacheService.findApiResponse(cacheKey) : Optional.empty();
    }

    private EnrichedMetadata buildEnrichedMetadata(SeriesParser.SeriesInfo info, TmdbEpisode episode) {
        Map<String, String> extra = new HashMap<>();
        if (episode.name() != null) extra.put("episodeTitle", episode.name());
        return new EnrichedMetadata(
                Optional.ofNullable(episode.showName()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.unmodifiableMap(extra));
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
    record TmdbTvSearchResponse(List<TmdbShow> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbShow(int id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbEpisode(String name, String showName) {}
}
