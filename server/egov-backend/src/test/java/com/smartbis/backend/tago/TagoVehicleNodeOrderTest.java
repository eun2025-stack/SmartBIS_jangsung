package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.w3c.dom.Element;

class TagoVehicleNodeOrderTest {
    @Test void t8KeepsMockRawUpAndDownOrdersWithoutAssumingRouteMinimum() throws Exception {
        var jdbc = new JdbcTemplate() {
            @Override public <T> java.util.List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                return java.util.List.of();
            }
        };
        var service = new TagoVehicleLocationService(jdbc, "", "AUTO", "36450", "http://localhost",
                false, false, 10000, true, false, HttpClient.newHttpClient());
        Method method = TagoVehicleLocationService.class.getDeclaredMethod("positionFrom", String.class, Element.class);
        method.setAccessible(true);
        Element up = TagoVehicleLocationService.parseXml(item("UP", "0")).getDocumentElement();
        Element down = TagoVehicleLocationService.parseXml(item("DOWN", "76")).getDocumentElement();
        var upPosition = (TagoVehicleLocationService.VehiclePosition) method.invoke(service, "TSB342000143", up);
        var downPosition = (TagoVehicleLocationService.VehiclePosition) method.invoke(service, "TSB342000143", down);
        assertEquals(0, upPosition.nodeOrder());
        assertEquals(76, downPosition.nodeOrder());
    }

    private static byte[] item(String direction, String order) {
        return ("<item><vehicleno>전남76아757</vehicleno><nodeid>757</nodeid><nodeord>" + order
                + "</nodeord><gpslati>35.3</gpslati><gpslong>126.7</gpslong><updowncd>" + direction
                + "</updowncd></item>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
