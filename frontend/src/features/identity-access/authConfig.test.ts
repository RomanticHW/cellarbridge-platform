import { beforeEach, describe, expect, it } from 'vitest';
import { AUTH_RETURN_TO_KEY, oidcConfig } from './authConfig';

describe('OIDC callback handling', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    window.history.replaceState({}, '', '/');
  });

  it('returns only to a protected application path and consumes stored navigation state', () => {
    window.sessionStorage.setItem(AUTH_RETURN_TO_KEY, '/app/profile');

    oidcConfig.onSigninCallback?.();

    expect(window.location.pathname).toBe('/app/profile');
    expect(window.sessionStorage.getItem(AUTH_RETURN_TO_KEY)).toBeNull();
  });

  it('rejects an external callback return target', () => {
    window.sessionStorage.setItem(AUTH_RETURN_TO_KEY, 'https://outside.example/path');

    oidcConfig.onSigninCallback?.();

    expect(window.location.pathname).toBe('/app');
  });

  it('rejects a same-origin path outside the protected app route', () => {
    window.sessionStorage.setItem(AUTH_RETURN_TO_KEY, '/application');

    oidcConfig.onSigninCallback?.();

    expect(window.location.pathname).toBe('/app');
  });

  it('uses Authorization Code with PKCE-compatible public-client settings and session storage', () => {
    expect(oidcConfig.response_type).toBe('code');
    expect(oidcConfig.userStore).toBeDefined();
    expect(window.localStorage).toHaveLength(0);
  });
});
