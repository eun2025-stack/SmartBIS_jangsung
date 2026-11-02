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
    private String laneAStart = "05:15";
    private String laneAEnd = "21:15";
    private int laneAIntervalSeconds = 600;
    private String laneCStart = "06:00";
    private String laneCEnd = "21:00";
    private int laneCIntervalSeconds = 1800;
    private int laneCInterRouteDelaySeconds = 2;
    private int laneC429DelayStep2Seconds = 3;
    private int laneC429DelayStep3Seconds = 5;
    private int localLeadMinutes = 15;
    private int variantLeadMinutes = 10;
    private int windowEndPaddingMinutes = 10;
    private double defaultMinutesPerStop = 1.2;
    private int defaultOneWayMinutes = 60;
    private int closeAfterLastMinutes = 5;
    private int noObservationMaxMinutes = 60;
    private java.util.Set<java.time.LocalDate> holidays = new java.util.HashSet<>();
    private final List<String> variantRouteIds = new ArrayList<>();
    private final Group local = new Group();
    private final Group hundred = new Group();

    public void validate() {
        if (dailyLimit < 1 || dailyLimit > 9500 || developerAccountLimit < dailyLimit || minGapMs < 10000 || maxRetries < 0 || maxRetries > 2
                || retryDelayMs < Math.max(10000, minGapMs) || local.intervalSeconds < 1 || hundred.intervalSeconds < 1
                || laneAIntervalSeconds < 1 || laneCIntervalSeconds < 1 || laneCInterRouteDelaySeconds < 1
                || laneC429DelayStep2Seconds < laneCInterRouteDelaySeconds || laneC429DelayStep3Seconds < laneC429DelayStep2Seconds
                || localLeadMinutes < 0 || variantLeadMinutes < 0 || windowEndPaddingMinutes < 0
                || defaultMinutesPerStop <= 0 || defaultOneWayMinutes < 1
                || closeAfterLastMinutes < 0 || noObservationMaxMinutes < 1) {
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
    public String getLaneAStart() { return laneAStart; }
    public void setLaneAStart(String value) { laneAStart = value; }
    public String getLaneAEnd() { return laneAEnd; }
    public void setLaneAEnd(String value) { laneAEnd = value; }
    public int getLaneAIntervalSeconds() { return laneAIntervalSeconds; }
    public void setLaneAIntervalSeconds(int value) { laneAIntervalSeconds = value; }
    public String getLaneCStart() { return laneCStart; }
    public void setLaneCStart(String value) { laneCStart = value; }
    public String getLaneCEnd() { return laneCEnd; }
    public void setLaneCEnd(String value) { laneCEnd = value; }
    public int getLaneCIntervalSeconds() { return laneCIntervalSeconds; }
    public void setLaneCIntervalSeconds(int value) { laneCIntervalSeconds = value; }
    public int getLaneCInterRouteDelaySeconds() { return laneCInterRouteDelaySeconds; }
    public void setLaneCInterRouteDelaySeconds(int value) { laneCInterRouteDelaySeconds = value; }
    public int getLaneC429DelayStep2Seconds() { return laneC429DelayStep2Seconds; }
    public void setLaneC429DelayStep2Seconds(int value) { laneC429DelayStep2Seconds = value; }
    public int getLaneC429DelayStep3Seconds() { return laneC429DelayStep3Seconds; }
    public void setLaneC429DelayStep3Seconds(int value) { laneC429DelayStep3Seconds = value; }
    public int getLocalLeadMinutes() { return localLeadMinutes; }
    public void setLocalLeadMinutes(int value) { localLeadMinutes = value; }
    public int getVariantLeadMinutes() { return variantLeadMinutes; }
    public void setVariantLeadMinutes(int value) { variantLeadMinutes = value; }
    public int getWindowEndPaddingMinutes() { return windowEndPaddingMinutes; }
    public void setWindowEndPaddingMinutes(int value) { windowEndPaddingMinutes = value; }
    public double getDefaultMinutesPerStop() { return defaultMinutesPerStop; }
    public void setDefaultMinutesPerStop(double value) { defaultMinutesPerStop = value; }
    public int getDefaultOneWayMinutes() { return defaultOneWayMinutes; }
    public void setDefaultOneWayMinutes(int value) { defaultOneWayMinutes = value; }
    public List<String> getVariantRouteIds() { return variantRouteIds; }
    public void setVariantRouteIds(List<String> value) { variantRouteIds.clear(); if (value != null) variantRouteIds.addAll(value); }
    public Group getLocal() { return local; }
    public Group getHundred() { return hundred; }
    public int getCloseAfterLastMinutes() { return closeAfterLastMinutes; }
    public void setCloseAfterLastMinutes(int value) { closeAfterLastMinutes=value; }
    public int getNoObservationMaxMinutes() { return noObservationMaxMinutes; }
    public void setNoObservationMaxMinutes(int value) { noObservationMaxMinutes=value; }
    public java.util.Set<java.time.LocalDate> getHolidays() { return java.util.Set.copyOf(holidays); }
    public void setHolidays(java.util.Set<java.time.LocalDate> value) { holidays=new java.util.HashSet<>(value); }

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
