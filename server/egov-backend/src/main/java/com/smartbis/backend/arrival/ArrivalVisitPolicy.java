package com.smartbis.backend.arrival;

/** Rev17: first trip visit, even target order, or overdue; never more than one HTTP attempt per visit. */
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
