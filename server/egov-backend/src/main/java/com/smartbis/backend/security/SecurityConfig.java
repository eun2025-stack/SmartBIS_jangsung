package com.smartbis.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import jakarta.servlet.DispatcherType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf
                        // The dashboard fetches the session token as JSON and echoes it in a request header.
                        // Use the same unmasked token representation for both operations; CSRF remains enabled.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // Android clients do not have an administrator session/CSRF token.
                // This endpoint accepts only a bounded status snapshot, never display commands.
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/v1/device-status", "POST"),
                                new AntPathRequestMatcher("/v1/device-enrollment/claim", "POST"),
                                new AntPathRequestMatcher("/v1/device-vehicle-assignment", "POST"),
                                new AntPathRequestMatcher("/v1/device-vehicle-assignment", "DELETE")))
                .authorizeHttpRequests(authorize -> authorize
                        // Preserve the original 4xx/5xx response instead of denying its servlet error dispatch.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/csrf", "/dashboard/login.html", "/dashboard/login",
                                "/dashboard/logout", "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/v1/contents", "/v1/media/**",
                                "/v1/display/**", "/v1/arrival", "/v1/device-status",
                                "/v1/vehicles", "/v1/vehicles/**", "/v1/device-enrollment/claim",
                                "/v1/device-vehicle-assignment", "/v1/device-vehicle-assignment/**").permitAll()
                        .requestMatchers("/dashboard/**", "/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception.accessDeniedHandler((request, response, denied) -> {
                            log.warn("Authorization denied: method={} path={} cause={}", request.getMethod(),
                                    request.getRequestURI(), denied.getClass().getSimpleName());
                            response.sendError(HttpStatus.FORBIDDEN.value());
                        }).defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/v1/admin/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/dashboard/login.html"),
                                new AntPathRequestMatcher("/dashboard/**")))
                .formLogin(form -> form.loginPage("/dashboard/login.html")
                        .loginProcessingUrl("/dashboard/login")
                        .defaultSuccessUrl("/dashboard/index.html", true)
                        .failureUrl("/dashboard/login.html?error").permitAll())
                .logout(logout -> logout.logoutUrl("/dashboard/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService dashboardAdmin(
            PasswordEncoder encoder,
            @Value("${DASHBOARD_ADMIN_USERNAME:}") String username,
            @Value("${DASHBOARD_ADMIN_PASSWORD:}") String password) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("DASHBOARD_ADMIN_USERNAME and DASHBOARD_ADMIN_PASSWORD must be set");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("ADMIN").build());
    }
}
