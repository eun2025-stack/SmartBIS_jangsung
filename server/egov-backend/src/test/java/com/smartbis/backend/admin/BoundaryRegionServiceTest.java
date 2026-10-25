package com.smartbis.backend.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryRegionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void matchesInteriorAndBoundaryPointInPolygon() throws Exception {
        JsonNode coordinates = mapper.readTree("[[[0,0],[10,0],[10,10],[0,10],[0,0]]]");
        var polygon = new BoundaryRegionService.Boundary("1", "area", "parent", "Polygon", coordinates);

        assertTrue(BoundaryRegionService.contains(polygon, 5, 5));
        assertTrue(BoundaryRegionService.contains(polygon, 0, 5));
        assertFalse(BoundaryRegionService.contains(polygon, 12, 5));
    }

    @Test
    void excludesPolygonHole() throws Exception {
        JsonNode coordinates = mapper.readTree("[[[0,0],[10,0],[10,10],[0,10],[0,0]],"
                + "[[4,4],[6,4],[6,6],[4,6],[4,4]]]");
        var polygon = new BoundaryRegionService.Boundary("1", "area", "parent", "Polygon", coordinates);

        assertTrue(BoundaryRegionService.contains(polygon, 2, 2));
        assertFalse(BoundaryRegionService.contains(polygon, 5, 5));
    }

    @Test
    void handlesMultiPolygon() throws Exception {
        JsonNode coordinates = mapper.readTree("[[[[0,0],[2,0],[2,2],[0,2],[0,0]]],"
                + "[[[5,5],[7,5],[7,7],[5,7],[5,5]]]]");
        var polygon = new BoundaryRegionService.Boundary("1", "area", "parent", "MultiPolygon", coordinates);

        assertTrue(BoundaryRegionService.contains(polygon, 1, 1));
        assertTrue(BoundaryRegionService.contains(polygon, 6, 6));
        assertFalse(BoundaryRegionService.contains(polygon, 3, 3));
    }

    @Test
    void selectsOnlyJangseongAndGwangjuFeatures() {
        assertTrue(BoundaryRegionService.isTargetArea("전남광주통합특별시 장성군 장성읍"));
        assertTrue(BoundaryRegionService.isTargetArea("전남광주통합특별시 광주광역시 광산구 신가동"));
        assertTrue(BoundaryRegionService.isTargetArea("전남광주통합특별시 광산구 신가동"));
        assertTrue(BoundaryRegionService.isTargetArea("전남광주통합특별시 북구 용봉동"));
        assertFalse(BoundaryRegionService.isTargetArea("전남광주통합특별시 담양군 담양읍"));
        assertFalse(BoundaryRegionService.isTargetArea("전남광주통합특별시 나주시 빛가람동"));
    }

    @Test
    void keepsJangseongNamesAndQualifiesGwangjuNames() {
        var jangseong = BoundaryRegionService.toBoundary("1", "장성읍",
                "전남광주통합특별시 장성군 장성읍", "Polygon", mapper.createArrayNode());
        var gwangju = BoundaryRegionService.toBoundary("2", "신가동",
                "전남광주통합특별시 광주광역시 광산구 신가동", "Polygon", mapper.createArrayNode());
        var updatedApiName = BoundaryRegionService.toBoundary("3", "신가동",
                "전남광주통합특별시 광산구 신가동", "Polygon", mapper.createArrayNode());

        org.junit.jupiter.api.Assertions.assertEquals("장성읍", jangseong.name());
        org.junit.jupiter.api.Assertions.assertEquals("장성군", jangseong.parentName());
        org.junit.jupiter.api.Assertions.assertEquals("광주광역시 광산구 신가동", gwangju.name());
        org.junit.jupiter.api.Assertions.assertEquals("광주광역시 광산구", gwangju.parentName());
        org.junit.jupiter.api.Assertions.assertEquals("광주광역시 광산구 신가동", updatedApiName.name());
        org.junit.jupiter.api.Assertions.assertEquals("광주광역시 광산구", updatedApiName.parentName());
    }
}
