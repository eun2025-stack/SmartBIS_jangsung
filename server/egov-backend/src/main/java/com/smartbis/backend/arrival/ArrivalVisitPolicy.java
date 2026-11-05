package com.smartbis.backend.arrival;

/** One logical result per visit. HTTP failure recovery stays within that reservation. */
public final class ArrivalVisitPolicy {
    private ArrivalVisitPolicy() {}
    public static boolean shouldQuery(int targetOrder,int minimumOrder,int currentOrder,int attempts,boolean firstTripVisit,boolean overdue,int stopStep) {
        if (stopStep!=2) throw new IllegalArgumentException("ARRIVAL_STOP_STEP_FIXED_2");
        return attempts==0 && targetOrder>=currentOrder && targetOrder>=minimumOrder
            && (Math.floorMod((long)targetOrder,stopStep)==0 || firstTripVisit || overdue);
    }
}
