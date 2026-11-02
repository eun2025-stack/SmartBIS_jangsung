package com.smartbis.backend.arrival;

import java.time.Instant;

/** Pure policy. Instants are persisted; neither API nor UI ever requests TAGO. */
public record ArrivalRulePolicy(long requerySeconds,long longEtaSeconds,long halfLowerSeconds,
        long soonSeconds,double halfRatio,long halfMinimumSeconds,long passageGraceSeconds) {
    public ArrivalRulePolicy {
        if (requerySeconds<=0 || halfLowerSeconds<=soonSeconds || longEtaSeconds<=halfLowerSeconds
                || soonSeconds<=0 || halfRatio<=0 || halfRatio>=1 || halfMinimumSeconds<=0 || passageGraceSeconds<=0)
            throw new IllegalArgumentException("Invalid arrival rules");
    }
    public static ArrivalRulePolicy defaults() { return new ArrivalRulePolicy(60,180,60,30,.5,30,60); }
    /** Rev17 hard cap includes failed attempts. Old ETA-band requery rules are retired. */
    public Instant nextQuery(Instant queriedAt,Integer etaSeconds,int attempts) {
        return attempts>=1 ? null : queriedAt;
    }
    public String display(Instant expected,Instant passedAt,Instant now) {
        if (expected==null) return "정보 없음";
        if (passedAt!=null && !passedAt.isAfter(now) && !passedAt.isAfter(expected.plusSeconds(passageGraceSeconds))) return "통과";
        if (now.isAfter(expected.plusSeconds(passageGraceSeconds))) return "통과 추정";
        if (!now.isBefore(expected.minusSeconds(soonSeconds))) return "곧 도착";
        return "도착 예정";
    }
}
