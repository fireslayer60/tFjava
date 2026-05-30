package io.testkit.api;

import io.testkit.core.TestKitException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Thin wrapper around OkHttp with TestKit-specific error handling. */
final class HttpTestClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTestClient.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ApiConfig config;

    HttpTestClient(ApiConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(config.followRedirects())
                .build();
    }

    ApiResponse execute(RequestSpec spec) {
        String url = buildUrl(spec);
        Request.Builder rb = new Request.Builder().url(url);

        // Default headers first, then per-request headers (can override defaults)
        config.defaultHeaders().forEach(rb::header);
        spec.headers().forEach(rb::header);

        RequestBody body = buildBody(spec);

        rb.method(spec.method().name(), body);

        log.debug("{} {} (timeout {}ms)", spec.method(), url, config.timeout().toMillis());

        long start = System.currentTimeMillis();
        try (Response response = client.newCall(rb.build()).execute()) {
            long duration = System.currentTimeMillis() - start;
            String responseBody = response.body() != null ? response.body().string() : "";
            Map<String, List<String>> headers = normaliseHeaders(response.headers());
            return new ApiResponse(response.code(), headers, responseBody, duration);
        } catch (IOException e) {
            throw new TestKitException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    private String buildUrl(RequestSpec spec) {
        String base = spec.baseUrl() != null ? spec.baseUrl() : config.baseUrl();
        String path = spec.path();

        // Substitute path params
        for (Map.Entry<String, String> entry : spec.pathParams().entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        String url = base.endsWith("/") && path.startsWith("/")
                ? base + path.substring(1)
                : base.endsWith("/") || path.startsWith("/")
                ? base + path
                : base + "/" + path;

        // Query params
        if (!spec.queryParams().isEmpty()) {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) throw new TestKitException("Invalid URL: " + url);
            HttpUrl.Builder ub = parsed.newBuilder();
            spec.queryParams().forEach(ub::addQueryParameter);
            url = ub.build().toString();
        }
        return url;
    }

    private RequestBody buildBody(RequestSpec spec) {
        if (spec.rawBody() != null) {
            String ct = spec.headers().getOrDefault("Content-Type", "application/octet-stream");
            return RequestBody.create(spec.rawBody(), MediaType.parse(ct));
        }
        if (spec.jsonBody() != null) {
            return RequestBody.create(spec.jsonBody(), JSON_MEDIA);
        }
        if (spec.formParams() != null && !spec.formParams().isEmpty()) {
            FormBody.Builder fb = new FormBody.Builder();
            spec.formParams().forEach(fb::add);
            return fb.build();
        }
        // methods that require a body but have none
        if (needsBody(spec.method())) return RequestBody.create(new byte[0]);
        return null;
    }

    private boolean needsBody(HttpMethod m) {
        return m == HttpMethod.POST || m == HttpMethod.PUT || m == HttpMethod.PATCH;
    }

    private Map<String, List<String>> normaliseHeaders(Headers headers) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name.toLowerCase(), headers.values(name));
        }
        return map;
    }
}
