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
    /** Total cap is immutable: initial + at most one follow-up, including failed attempts. */
    public Instant nextQuery(Instant queriedAt,Integer etaSeconds,int attempts) {
        if (attempts>=2) return null;
        if (etaSeconds==null) return queriedAt.plusSeconds(requerySeconds);
        long delay=etaSeconds>=longEtaSeconds ? requerySeconds
            : etaSeconds>=halfLowerSeconds ? Math.max(halfMinimumSeconds,(long)Math.ceil(etaSeconds*halfRatio))
            : Math.max(1,etaSeconds);
        return queriedAt.plusSeconds(delay);
    }
    public String display(Instant expected,Instant passedAt,Instant now) {
        if (expected==null) return "정보 없음";
        if (passedAt!=null && !passedAt.isAfter(now) && !passedAt.isAfter(expected.plusSeconds(passageGraceSeconds))) return "통과";
        if (now.isAfter(expected.plusSeconds(passageGraceSeconds))) return "통과 추정";
        if (!now.isBefore(expected.minusSeconds(soonSeconds))) return "곧 도착";
        return "도착 예정";
    }
}
