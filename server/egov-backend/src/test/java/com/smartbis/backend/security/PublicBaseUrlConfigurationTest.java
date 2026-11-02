package com.smartbis.backend.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PublicBaseUrlConfigurationTest {
    @Test void publicOriginIsConfiguredAndRelativeDefaultsStayEmpty() {
        assertEquals("",PublicBaseUrlConfiguration.origin(""));
        assertEquals("https://example.invalid:8443",PublicBaseUrlConfiguration.origin("https://example.invalid:8443/api"));
        assertThrows(IllegalArgumentException.class,()->PublicBaseUrlConfiguration.origin("file:///tmp"));
        assertThrows(IllegalArgumentException.class,()->PublicBaseUrlConfiguration.origin("https://user:pass@example.invalid"));
    }
}
