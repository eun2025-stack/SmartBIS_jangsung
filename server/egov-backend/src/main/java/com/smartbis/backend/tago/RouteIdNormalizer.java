package com.smartbis.backend.tago;

import java.util.Set;

/** Canonical IDs at every input boundary; unknown IDs stay explicitly unknown. */
public final class RouteIdNormalizer {
    private RouteIdNormalizer() {}
    public static String normalize(String input) {
        String id=input==null?"":input.trim();
        return id.startsWith("TBS342") ? "TSB"+id.substring(3) : id;
    }
    public record Match(String routeId, boolean known) {}
    public static Match match(String input, Set<String> catalog) {
        String id=normalize(input);
        return new Match(id,catalog.contains(id));
    }
}
