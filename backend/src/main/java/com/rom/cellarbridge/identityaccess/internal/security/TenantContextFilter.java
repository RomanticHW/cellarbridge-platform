package com.rom.cellarbridge.identityaccess.internal.security;

import com.rom.cellarbridge.identityaccess.TenantContext;
import com.rom.cellarbridge.identityaccess.TenantContextHolder;
import com.rom.cellarbridge.identityaccess.internal.application.IdentityAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class TenantContextFilter extends OncePerRequestFilter {

  static final String TENANT_HASH_MDC_KEY = "tenantHash";
  static final String SUBJECT_HASH_MDC_KEY = "subjectHash";

  private final IdentityAccessService identityAccessService;
  private final TenantContextHolder contextHolder;

  TenantContextFilter(
      IdentityAccessService identityAccessService, TenantContextHolder contextHolder) {
    this.identityAccessService = identityAccessService;
    this.contextHolder = contextHolder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      filterChain.doFilter(request, response);
      return;
    }

    TenantContext context = identityAccessService.resolve(jwtAuthentication.getToken());
    try (TenantContextHolder.Scope ignored = contextHolder.open(context);
        MDC.MDCCloseable ignoredTenant =
            MDC.putCloseable(TENANT_HASH_MDC_KEY, context.tenantHash());
        MDC.MDCCloseable ignoredSubject =
            MDC.putCloseable(SUBJECT_HASH_MDC_KEY, context.subjectHash())) {
      filterChain.doFilter(request, response);
    }
  }
}
