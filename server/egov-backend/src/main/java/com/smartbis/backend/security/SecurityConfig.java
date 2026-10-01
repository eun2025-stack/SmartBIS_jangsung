package com.smartbis.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/csrf", "/dashboard/login.html", "/dashboard/login",
                                "/dashboard/logout", "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/v1/contents", "/v1/media/**",
                                "/v1/display/**", "/v1/arrival").permitAll()
                        .requestMatchers("/dashboard/**", "/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(exception -> exception.defaultAuthenticationEntryPointFor(
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
