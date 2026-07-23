package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.TenantContextSnapshot;
import com.rom.cellarbridge.platform.mcp.McpReadExecutor;
import com.rom.cellarbridge.platform.mcp.McpSecurityProperties;
import com.rom.cellarbridge.platform.mcp.McpSecuritySupport;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  private static final String MCP_METADATA = "/.well-known/oauth-protected-resource/mcp";

  @Bean
  McpReadExecutor.ExecutionContext mcpExecutionContext(TenantContextHolder contexts) {
    return new McpReadExecutor.ExecutionContext() {
      @Override
      public <T> Callable<T> wrap(Callable<T> task) {
        return TenantContextSnapshot.capture(contexts).wrap(contexts, task);
      }
    };
  }

  @Bean
  @Order(1)
  @ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
  SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      SecurityProperties securityProperties,
      TenantContextFilter tenantContextFilter,
      McpSecuritySupport mcp)
      throws Exception {
    McpSecurityProperties properties = mcp.properties();
    http.securityMatcher("/mcp", MCP_METADATA)
        .csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, MCP_METADATA)
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/mcp")
                    .permitAll()
                    .anyRequest()
                    .hasAuthority("SCOPE_" + properties.scope()))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(
                        jwt ->
                            jwt.decoder(
                                JwtConfiguration.validated(
                                    decoder, JwtConfiguration.mcpValidators(properties))))
                    .protectedResourceMetadata(
                        metadata ->
                            metadata.protectedResourceMetadataCustomizer(
                                builder ->
                                    builder
                                        .resource(properties.resource())
                                        .claims(
                                            claims -> {
                                              claims.put(
                                                  "authorization_servers",
                                                  List.of(securityProperties.issuer()));
                                              claims.put(
                                                  "bearer_methods_supported", List.of("header"));
                                              claims.put(
                                                  "scopes_supported", List.of(properties.scope()));
                                              claims.remove(
                                                  "tls_client_certificate_bound_access_tokens");
                                            })))
                    .authenticationEntryPoint(mcp.authenticationEntryPoint())
                    .accessDeniedHandler(mcp.accessDeniedHandler()))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(mcp.authenticationEntryPoint())
                    .accessDeniedHandler(mcp.accessDeniedHandler()));
    configureHeaders(http);
    http.addFilterBefore(mcp.ingressFilter(), org.springframework.web.filter.CorsFilter.class);
    http.addFilterAfter(mcp.admissionFilter(), BearerTokenAuthenticationFilter.class);
    http.addFilterBefore(tenantContextFilter, AuthorizationFilter.class);
    http.addFilterAfter(new McpProtocolVersionFilter(), AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      SecurityProperties properties,
      TenantContextFilter tenantContextFilter,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.securityMatcher(
            new NegatedRequestMatcher(PathPatternRequestMatcher.pathPattern(MCP_METADATA)))
        .csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
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
                    .requestMatchers("/api/v1/receivables/**")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/dashboard",
                        "/api/v1/audit/entries",
                        "/api/v1/timeline",
                        "/api/v1/work-items")
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
                    .jwt(
                        jwt ->
                            jwt.decoder(
                                JwtConfiguration.validated(
                                    decoder,
                                    JwtConfiguration.validators(
                                        properties.issuer(), properties.audience()))))
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler));
    configureHeaders(http);
    http.addFilterBefore(tenantContextFilter, AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      SecurityProperties properties, McpSecurityProperties mcpProperties) {
    return corsConfigurationSource(properties, mcpProperties.allowedOrigins());
  }

  CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
    return corsConfigurationSource(properties, properties.allowedOrigins());
  }

  private CorsConfigurationSource corsConfigurationSource(
      SecurityProperties properties, List<String> mcpAllowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "If-Match",
            "Idempotency-Key",
            "MCP-Protocol-Version",
            "X-Correlation-ID"));
    configuration.setExposedHeaders(List.of("ETag", "X-Correlation-ID", "Idempotency-Replayed"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    CorsConfiguration mcp = new CorsConfiguration();
    mcp.setAllowedOrigins(mcpAllowedOrigins);
    mcp.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    mcp.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "MCP-Protocol-Version", "X-Correlation-ID"));
    mcp.setExposedHeaders(List.of("X-Correlation-ID"));
    mcp.setAllowCredentials(false);
    mcp.setMaxAge(3600L);
    source.registerCorsConfiguration("/mcp", mcp);
    return source;
  }

  private static void configureHeaders(HttpSecurity http) throws Exception {
    http.headers(
        headers ->
            headers
                .contentSecurityPolicy(
                    policy -> policy.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(frameOptions -> frameOptions.deny())
                .referrerPolicy(
                    referrer ->
                        referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .addHeaderWriter(
                    new PermissionsPolicyHeaderWriter("camera=(), geolocation=(), microphone=()")));
  }
}
