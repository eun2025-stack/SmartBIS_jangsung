package com.smartbis.backend.tago;

final class TagoApiRequestThrottle {
    static final long MIN_REQUEST_GAP_MILLIS = 10_000L;

    private static long lastRequestAtMillis;

    private TagoApiRequestThrottle() {
    }

    static synchronized void awaitRequestSlot() throws InterruptedException {
        long waitMillis = MIN_REQUEST_GAP_MILLIS
                - (System.currentTimeMillis() - lastRequestAtMillis);
        if (waitMillis > 0) {
            Thread.sleep(waitMillis);
        }
        lastRequestAtMillis = System.currentTimeMillis();
    }
}