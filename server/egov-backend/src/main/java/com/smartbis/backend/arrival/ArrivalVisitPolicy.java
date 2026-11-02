package com.smartbis.backend.arrival;

/** One logical result per visit. HTTP failure recovery stays within that reservation. */
public final class ArrivalVisitPolicy {
    private ArrivalVisitPolicy() {}
    public static boolean shouldQuery(int targetOrder,int attempts,boolean firstTripVisit,boolean overdue) {
        return shouldQuery(targetOrder,attempts,firstTripVisit,overdue,2);
    }
    public static boolean shouldQuery(int targetOrder,int attempts,boolean firstTripVisit,boolean overdue,int stopStep) {
        if (stopStep!=2) throw new IllegalArgumentException("ARRIVAL_STOP_STEP_FIXED_2");
        return targetOrder>=0 && attempts<1 && (firstTripVisit || targetOrder%stopStep==0 || overdue);
    }
}
