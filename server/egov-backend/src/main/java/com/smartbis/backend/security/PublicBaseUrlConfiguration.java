package com.smartbis.backend.security;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.*;

/** An optional external origin. Relative login redirects and host-only cookies stay relative/host-only. */
@Configuration
public class PublicBaseUrlConfiguration {
    static String origin(String value) {
        if(value==null||value.isBlank()) return "";
        URI uri=URI.create(value);
        if(!List.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
                || uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("Invalid PUBLIC_BASE_URL");
        return uri.getScheme()+"://"+uri.getRawAuthority();
    }
    @Bean
    CorsConfigurationSource publicCorsConfiguration(@Value("${public.base-url}") String value) {
        var config=new CorsConfiguration();
        String origin=origin(value);
        if(!origin.isEmpty()) config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type","X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**",config);
        return source;
    }
}
