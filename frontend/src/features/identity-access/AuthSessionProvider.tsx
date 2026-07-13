import type { PropsWithChildren } from 'react';
import { AuthSessionContext, type AuthSession } from './authSession';

interface AuthSessionProviderProps extends PropsWithChildren {
  value: AuthSession;
}

export function AuthSessionProvider({ value, children }: AuthSessionProviderProps) {
  return <AuthSessionContext value={value}>{children}</AuthSessionContext>;
}
