package com.yandu.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yandu.translation-sync")
public class TranslationSyncProperties {

    private boolean enabled;
    private String apiBaseUrl;
    private long projectId;
    private String locale;
    private String format = "json-flat-v1";
    private long refreshIntervalMs = 60_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public long getProjectId() { return projectId; }
    public void setProjectId(long projectId) { this.projectId = projectId; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public long getRefreshIntervalMs() { return refreshIntervalMs; }
    public void setRefreshIntervalMs(long refreshIntervalMs) { this.refreshIntervalMs = refreshIntervalMs; }
}
