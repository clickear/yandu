package com.yandu.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("yandu.translation-sync")
public class TranslationSyncProperties {

    private boolean enabled;
    private String mode = "polling";
    private String apiBaseUrl;
    private long projectId;
    /** @deprecated Use locales to synchronize more than one published language. */
    @Deprecated
    private String locale;
    private List<String> locales = new ArrayList<>();
    private String format = "json-flat-v1";
    private long refreshIntervalMs = 60_000;
    private String nettyHost;
    private int nettyPort = 9091;
    private String websocketUrl;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public long getProjectId() { return projectId; }
    public void setProjectId(long projectId) { this.projectId = projectId; }
    /** @deprecated Use getLocales(). */
    @Deprecated
    public String getLocale() { return locale; }
    /** @deprecated Use setLocales(). */
    @Deprecated
    public void setLocale(String locale) { this.locale = locale; }
    public List<String> getLocales() { return locales; }
    public void setLocales(List<String> locales) { this.locales = locales == null ? new ArrayList<>() : new ArrayList<>(locales); }

    public List<String> configuredLocales() {
        var configured = new java.util.LinkedHashSet<String>();
        if (locales != null) locales.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).forEach(configured::add);
        if (configured.isEmpty() && locale != null && !locale.isBlank()) configured.add(locale.trim());
        return List.copyOf(configured);
    }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public long getRefreshIntervalMs() { return refreshIntervalMs; }
    public void setRefreshIntervalMs(long refreshIntervalMs) { this.refreshIntervalMs = refreshIntervalMs; }
    public String getNettyHost() { return nettyHost; }
    public void setNettyHost(String nettyHost) { this.nettyHost = nettyHost; }
    public int getNettyPort() { return nettyPort; }
    public void setNettyPort(int nettyPort) { this.nettyPort = nettyPort; }
    public String getWebsocketUrl() { return websocketUrl; }
    public void setWebsocketUrl(String websocketUrl) { this.websocketUrl = websocketUrl; }
}
