package com.smartbis.backend.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PublicBaseUrlConfigurationTest {
    @Test void profilesRequirePublicBaseUrlWithoutSettingCookieDomain() throws Exception {
        var profile=new java.util.Properties();
        try(var stream=getClass().getResourceAsStream("/application-web2-observation.properties")) { profile.load(stream); }
        assertEquals("${PUBLIC_BASE_URL}",profile.getProperty("public.base-url"));
        var main=new java.util.Properties();
        try(var stream=getClass().getResourceAsStream("/application.properties")) { main.load(stream); }
        assertEquals("${PUBLIC_BASE_URL}",main.getProperty("public.base-url"));
        assertNull(profile.getProperty("server.servlet.session.cookie.domain"));
        assertNull(main.getProperty("server.servlet.session.cookie.domain"));
    }
    @Test void publicOriginIsConfiguredAndRelativeDefaultsStayEmpty() {
        assertEquals("",PublicBaseUrlConfiguration.origin(""));
        assertEquals("https://example.invalid:8443",PublicBaseUrlConfiguration.origin("https://example.invalid:8443/api"));
        assertThrows(IllegalArgumentException.class,()->PublicBaseUrlConfiguration.origin("file:///tmp"));
        assertThrows(IllegalArgumentException.class,()->PublicBaseUrlConfiguration.origin("https://user:pass@example.invalid"));
    }
}
