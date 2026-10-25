package com.smartbis.backend.arrival;

import java.math.BigDecimal;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ArrivalPrediction(
        String vehicleNumber,
        String routeId,
        String currentStop,
        String nextStop,
        String currentNodeId,
        String nextNodeId,
        BigDecimal distanceMeters,
        boolean within100m,
        BigDecimal distanceFromCurrentStopMeters,
        Integer estimatedArrivalMinutes,
        Long estimatedArrivalSeconds,
        String arrivalStatusText,
        @JsonProperty("estimated_arrival_at") Instant estimatedArrivalAt
) {
    public ArrivalPrediction(String vehicle,String route,String current,String next,String currentNode,String nextNode,
            BigDecimal distance,boolean within,BigDecimal distanceFrom,Integer minutes,Long seconds,String status) {
        this(vehicle,route,current,next,currentNode,nextNode,distance,within,distanceFrom,minutes,seconds,status,null);
    }
    public ArrivalPrediction(String vehicle,String route,String current,String next,String currentNode,String nextNode,
            BigDecimal distance,boolean within,BigDecimal distanceFrom,Integer minutes) {
        this(vehicle,route,current,next,currentNode,nextNode,distance,within,distanceFrom,minutes,null,null,null);
    }
}
