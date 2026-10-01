package com.smartbis.backend.admin;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/session")
public class AdminSessionController {
    @GetMapping
    public Map<String, Object> current(Authentication authentication) {
        return Map.of("username", authentication.getName(), "roles",
                authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList());
    }
}
