package com.plstk.loyaltybot.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Конфигурация Spring Security.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final Environment environment;
    
    @Value("${security.cors.allowed-origins:}")
    private String corsAllowedOrigins;
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> {
                auth
                    .requestMatchers(new AntPathRequestMatcher("/api/auth/**")).permitAll()
                    .requestMatchers(new AntPathRequestMatcher("/tg/webhook/**")).permitAll()
                    .requestMatchers(new AntPathRequestMatcher("/max/webhook/**")).permitAll()
                    .requestMatchers(new AntPathRequestMatcher("/actuator/health")).permitAll()
                    .requestMatchers(new AntPathRequestMatcher("/actuator/info")).permitAll()
                    .requestMatchers(new AntPathRequestMatcher("/actuator/**")).authenticated()
                    .requestMatchers(
                        new AntPathRequestMatcher("/"),
                        new AntPathRequestMatcher("/index.html"),
                        new AntPathRequestMatcher("/assets/**"),
                        new AntPathRequestMatcher("/*.js"),
                        new AntPathRequestMatcher("/*.css"),
                        new AntPathRequestMatcher("/*.ico")
                    ).permitAll()
                    .requestMatchers(new AntPathRequestMatcher("/api/**")).authenticated();
                
                if (!isProd) {
                    auth.requestMatchers(new AntPathRequestMatcher("/h2-console/**")).permitAll();
                }
                
                auth.anyRequest().permitAll();
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        if (isProd) {
            http.headers(headers -> headers
                .contentTypeOptions(ct -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
            );
        } else {
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }
        
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        if (StringUtils.hasText(corsAllowedOrigins)) {
            configuration.setAllowedOrigins(
                Arrays.asList(corsAllowedOrigins.split(","))
            );
        } else {
            configuration.setAllowedOriginPatterns(List.of("http://localhost:*"));
        }
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
