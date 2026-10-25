package com.smartbis.backend.tago;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational location-only settings. No observation settings are overridden. */
@ConfigurationProperties(prefix = "tago.location.operations")
public class LocationCollectionProperties {
    private boolean enabled = false;
    private int dailyLimit = 9500;
    private int developerAccountLimit = 10000;
    private long minGapMs = 10000;
    private int maxRetries = 2;
    private long retryDelayMs = 10000;
    private String zone = "Asia/Seoul";
    private final Group local = new Group();
    private final Group hundred = new Group();

    public void validate() {
        if (dailyLimit < 1 || dailyLimit > 9500 || developerAccountLimit < dailyLimit || minGapMs < 10000 || maxRetries < 0 || maxRetries > 2
                || retryDelayMs < Math.max(10000, minGapMs) || local.intervalSeconds < 1 || hundred.intervalSeconds < 1) {
            throw new IllegalArgumentException("Invalid location guard budget/retry/interval settings");
        }
        ZoneId.of(zone);
        var ids = new HashSet<String>();
        for (String id : local.routeIds) {
            if (id == null || id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("Invalid/duplicate local route");
        }
        for (String id : hundred.routeIds) {
            if (id == null || id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("Duplicate route groups");
        }
        if (local.enabled && local.routeIds.isEmpty() || hundred.enabled && hundred.routeIds.isEmpty()) {
            throw new IllegalArgumentException("Enabled route group requires a configured route list");
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int value) { dailyLimit = value; }
    public int getDeveloperAccountLimit() { return developerAccountLimit; }
    public void setDeveloperAccountLimit(int value) { developerAccountLimit = value; }
    public long getMinGapMs() { return minGapMs; }
    public void setMinGapMs(long value) { minGapMs = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) { maxRetries = value; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long value) { retryDelayMs = value; }
    public String getZone() { return zone; }
    public void setZone(String value) { zone = value; }
    public Group getLocal() { return local; }
    public Group getHundred() { return hundred; }

    public static class Group {
        private boolean enabled = false;
        private int intervalSeconds = 600;
        private List<String> routeIds = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int value) { intervalSeconds = value; }
        public List<String> getRouteIds() { return routeIds; }
        public void setRouteIds(List<String> value) { routeIds = new ArrayList<>(value); }
    }
}
