import { createContext, useContext } from 'react';

export interface AuthSession {
  isLoading: boolean;
  isAuthenticated: boolean;
  accessToken?: string;
  sessionIdentity?: string;
  errorMessage?: string;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  clearSession: () => Promise<void>;
}

export const AuthSessionContext = createContext<AuthSession | null>(null);

export function useAuthSession(): AuthSession {
  const session = useContext(AuthSessionContext);
  if (session === null) {
    throw new Error('Auth session is not available');
  }
  return session;
}
