package com.rom.cellarbridge.identityaccess.internal.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      TenantContextFilter tenantContextFilter,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/me")
                    .authenticated()
                    .requestMatchers("/api/v1/partners/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/catalog/skus/**")
                    .authenticated()
                    .requestMatchers("/api/v1/inventory/reservations/**")
                    .authenticated()
                    .requestMatchers("/api/v1/fulfillment/plans/**")
                    .authenticated()
                    .requestMatchers("/api/v1/exceptions/**", "/api/v1/event-publications/**")
                    .authenticated()
                    .requestMatchers("/api/v1/orders/**", "/api/v1/buyer/orders/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/portal/quotations/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/portal/quotations/*/acceptance",
                        "/api/v1/portal/quotations/*/rejection")
                    .permitAll()
                    .requestMatchers("/api/v1/quotations/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        policy ->
                            policy.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .frameOptions(frameOptions -> frameOptions.deny())
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .addHeaderWriter(
                        new PermissionsPolicyHeaderWriter(
                            "camera=(), geolocation=(), microphone=()")));
    http.addFilterBefore(tenantContextFilter, AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            "Authorization", "Content-Type", "If-Match", "Idempotency-Key", "X-Correlation-ID"));
    configuration.setExposedHeaders(List.of("ETag", "X-Correlation-ID", "Idempotency-Replayed"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }
}
