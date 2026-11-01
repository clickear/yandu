package com.yandu.translation.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yandu.translation.application.MachineTranslationProvider;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** LibreTranslate-compatible HTTP adapter. Credentials remain server-side configuration. */
public class LibreTranslateProvider implements MachineTranslationProvider {

    private final URI baseUri;
    private final String apiKey;
    private final RestClient client;

    public LibreTranslateProvider(
            RestClient.Builder builder,
            @Value("${yandu.machine-translation.base-url:}") String baseUrl,
            @Value("${yandu.machine-translation.api-key:}") String apiKey,
            @Value("${yandu.machine-translation.timeout-ms:30000}") int timeoutMs) {
        this.baseUri = parseBaseUri(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (timeoutMs < 1000 || timeoutMs > 120_000) {
            throw new IllegalArgumentException("机器翻译服务超时时间必须介于 1000 和 120000 毫秒。");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 5000));
        requestFactory.setReadTimeout(timeoutMs);
        this.client = builder.requestFactory(requestFactory).build();
    }

    @Override
    public boolean isConfigured() { return baseUri != null; }

    @Override
    public String translate(String sourceText, String sourceLocale, String targetLocale) {
        if (!isConfigured()) throw new ProviderFailureException("机器翻译服务尚未配置。");
        var body = new LinkedHashMap<String, Object>();
        body.put("q", sourceText);
        body.put("source", providerLanguage(sourceLocale));
        body.put("target", providerLanguage(targetLocale));
        body.put("format", "text");
        if (!apiKey.isBlank()) body.put("api_key", apiKey);
        try {
            var response = client.post().uri(baseUri.resolve("translate"))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                    .body(TranslateResponse.class);
            if (response == null || response.translatedText() == null
                    || response.translatedText().isBlank()) {
                throw new ProviderFailureException("机器翻译服务返回了空译文。");
            }
            return response.translatedText();
        } catch (RestClientResponseException failure) {
            throw new ProviderFailureException("机器翻译服务请求失败（HTTP "
                    + failure.getStatusCode().value() + "）。");
        } catch (org.springframework.web.client.ResourceAccessException failure) {
            throw new ProviderFailureException("无法连接机器翻译服务，请检查服务地址和网络后重试。");
        }
    }

    private static String providerLanguage(String locale) {
        return locale.split("-", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static URI parseBaseUri(String value) {
        if (value == null || value.isBlank()) return null;
        var trimmed = value.trim().replaceAll("/+$", "");
        var uri = URI.create(trimmed + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("机器翻译服务地址必须使用 HTTP 或 HTTPS。");
        }
        return uri;
    }

    private record TranslateResponse(@JsonProperty("translatedText") String translatedText) { }
}
