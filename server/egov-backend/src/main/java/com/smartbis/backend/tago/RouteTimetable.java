package com.smartbis.backend.tago;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Immutable, reviewed source data. No dispatch assignment and no DB writes. */
final class RouteTimetable {
    record Departure(String route, LocalTime time, boolean holiday, String pattern) {}
    private final Set<String> all=new TreeSet<>(),laneA=new TreeSet<>(),regular=new TreeSet<>(),variant=new TreeSet<>();
    private final List<Departure> departures=new ArrayList<>();
    static final RouteTimetable DEFAULT=new RouteTimetable();
    private RouteTimetable() {
        for(String[] row:read("rev17-routes.csv")) {
            String id=RouteIdNormalizer.normalize(row[0]); all.add(id);
            if(row[1].equals("regular")) regular.add(id);
            if(row[1].equals("variant")) variant.add(id);
            if(Boolean.parseBoolean(row[2])) laneA.add(id);
        }
        for(String[] row:read("rev17-departures.csv"))
            departures.add(new Departure(RouteIdNormalizer.normalize(row[0]),LocalTime.parse(row[1]),row[2].equals("HOLIDAY"),row[3]));
        if(all.size()!=105 || laneA.size()!=3 || regular.size()!=86 || variant.size()!=14
                || !Collections.disjoint(laneA,laneB())) throw new IllegalStateException("REV17_ROUTE_DATA_INVALID");
    }
    private static List<String[]> read(String name) {
        try(var stream=RouteTimetable.class.getClassLoader().getResourceAsStream(name)) {
            if(stream==null) throw new IllegalStateException("Missing route resource");
            return new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines()
                .skip(1).filter(s->!s.isBlank()).map(s->s.split(",",-1)).toList();
        } catch(IOException e) { throw new IllegalStateException("Route resource unavailable",e); }
    }
    Set<String> all() { return Set.copyOf(all); }
    Set<String> laneA() { return Set.copyOf(laneA); }
    Set<String> regular() { return Set.copyOf(regular); }
    Set<String> variant() { return Set.copyOf(variant); }
    Set<String> laneB() { var result=new TreeSet<>(regular); result.addAll(variant); return Set.copyOf(result); }
    List<Departure> departures(String route,LocalDate day,Set<LocalDate> holidays) {
        boolean holiday=day.getDayOfWeek()==DayOfWeek.SATURDAY || day.getDayOfWeek()==DayOfWeek.SUNDAY || holidays.contains(day);
        return departures.stream().filter(d->d.route().equals(route)&&d.holiday()==holiday).toList();
    }
    List<RouteCallSchedule.Window> windows(String route,LocalDate day,LocationCollectionProperties settings) {
        if(!laneB().contains(route)) return List.of();
        var windows=departures(route,day,settings.getHolidays()).stream().map(d->
            LocationLaneSchedule.window(d.time().toSecondOfDay(),variant.contains(route)?settings.getVariantLeadMinutes():settings.getLocalLeadMinutes(),
                settings.getNoObservationMaxMinutes(),settings.getLocal().getIntervalSeconds())).toList();
        return LocationLaneSchedule.merge(windows).stream().map(w->new RouteCallSchedule.Window(w.start(),w.end(),w.intervalSeconds())).toList();
    }
    static long arrivalUpperBound(int trips,int stopCount) {
        if(trips<0||stopCount<0) throw new IllegalArgumentException("Invalid arrival count");
        return (long)trips*((stopCount+1)/2);
    }
}
