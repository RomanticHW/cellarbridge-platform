import { WebStorageStateStore } from 'oidc-client-ts';
import type { AuthProviderProps } from 'react-oidc-context';

export const AUTH_RETURN_TO_KEY = 'cellarbridge.auth.return-to';
export const SESSION_EXPIRED_KEY = 'cellarbridge.auth.session-expired';

const applicationOrigin = window.location.origin;

function safeReturnPath(): string {
  const candidate = window.sessionStorage.getItem(AUTH_RETURN_TO_KEY);
  window.sessionStorage.removeItem(AUTH_RETURN_TO_KEY);
  return candidate === '/app' || candidate?.startsWith('/app/') ? candidate : '/app';
}

export const oidcConfig = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8081/realms/cellarbridge',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'cellarbridge-console',
  redirect_uri: `${applicationOrigin}/app`,
  post_logout_redirect_uri: `${applicationOrigin}/login`,
  silent_redirect_uri: `${applicationOrigin}/app`,
  response_type: 'code',
  scope: 'openid profile',
  automaticSilentRenew: true,
  includeIdTokenInSilentRenew: true,
  loadUserInfo: false,
  monitorSession: false,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, safeReturnPath());
  },
} satisfies AuthProviderProps;
