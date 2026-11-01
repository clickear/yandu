package com.yandu.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yandu.translation-sync")
public class TranslationSyncProperties {

    private boolean enabled;
    private String mode = "polling";
    private String apiBaseUrl;
    private long projectId;
    private String locale;
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
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
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
