import { useCallback, useEffect, useMemo, type PropsWithChildren } from 'react';
import { useAuth } from 'react-oidc-context';
import { SESSION_EXPIRED_KEY } from './authConfig';
import type { AuthSession } from './authSession';
import { AuthSessionProvider } from './AuthSessionProvider';

export function AuthSessionBridge({ children }: PropsWithChildren) {
  const auth = useAuth();
  const { events, removeUser, signinRedirect, signinSilent, signoutRedirect } = auth;

  useEffect(() => {
    const removeExpiringListener = events.addAccessTokenExpiring(() => {
      void signinSilent();
    });
    const removeExpiredListener = events.addAccessTokenExpired(() => {
      window.sessionStorage.setItem(SESSION_EXPIRED_KEY, 'true');
      void removeUser();
    });
    return () => {
      removeExpiringListener();
      removeExpiredListener();
    };
  }, [events, removeUser, signinSilent]);

  const signIn = useCallback(async () => {
    await signinRedirect();
  }, [signinRedirect]);

  const signOut = useCallback(async () => {
    await signoutRedirect();
  }, [signoutRedirect]);

  const clearSession = useCallback(async () => {
    window.sessionStorage.setItem(SESSION_EXPIRED_KEY, 'true');
    await removeUser();
  }, [removeUser]);

  const session = useMemo<AuthSession>(
    () => ({
      isLoading: auth.isLoading || auth.activeNavigator !== undefined,
      isAuthenticated: auth.isAuthenticated,
      accessToken: auth.user?.access_token,
      sessionIdentity: auth.user?.profile.sub,
      errorMessage:
        auth.error === undefined ? undefined : 'The identity session could not be established.',
      signIn,
      signOut,
      clearSession,
    }),
    [
      auth.activeNavigator,
      auth.error,
      auth.isAuthenticated,
      auth.isLoading,
      auth.user?.access_token,
      auth.user?.profile.sub,
      clearSession,
      signIn,
      signOut,
    ],
  );

  return <AuthSessionProvider value={session}>{children}</AuthSessionProvider>;
}
