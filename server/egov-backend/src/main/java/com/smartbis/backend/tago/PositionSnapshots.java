package com.smartbis.backend.tago;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Current real observations exist even when observation-history storage is off. */
@Component
public class PositionSnapshots {
    public record Position(String vehicle,String route,String node,Integer order,Double latitude,Double longitude,Instant at) {}
    private final Map<String,Position> latest=new ConcurrentHashMap<>();
    public void observe(Position p,boolean interpolated) {
        if(!interpolated && p.vehicle()!=null && !p.vehicle().isBlank())
            latest.compute(p.vehicle(),(key,old)->old==null||!p.at().isBefore(old.at())?p:old);
    }
    public List<Position> all() { return List.copyOf(latest.values()); }
}
