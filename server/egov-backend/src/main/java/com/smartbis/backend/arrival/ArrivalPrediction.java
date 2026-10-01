package com.smartbis.backend.arrival;

import java.math.BigDecimal;

public record ArrivalPrediction(
        String vehicleNumber,
        String routeId,
        String currentStop,
        String nextStop,
        BigDecimal distanceMeters,
        boolean within100m
) {
}
