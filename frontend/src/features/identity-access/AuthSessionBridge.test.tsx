import { act, render, screen } from '@testing-library/react';
import { useAuth } from 'react-oidc-context';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_KEY } from './authConfig';
import { useAuthSession } from './authSession';
import { AuthSessionBridge } from './AuthSessionBridge';

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }));

function SessionProbe() {
  const session = useAuthSession();
  return <span>{session.isAuthenticated ? 'authenticated' : 'anonymous'}</span>;
}

describe('AuthSessionBridge', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
  });

  it('renews an expiring token, clears an expired token, and removes event listeners', async () => {
    let expiringListener: (() => void) | undefined;
    let expiredListener: (() => void) | undefined;
    const removeExpiring = vi.fn();
    const removeExpired = vi.fn();
    const signinSilent = vi.fn().mockResolvedValue(undefined);
    const removeUser = vi.fn().mockResolvedValue(undefined);

    vi.mocked(useAuth).mockReturnValue({
      isLoading: false,
      isAuthenticated: true,
      activeNavigator: undefined,
      error: undefined,
      user: {
        access_token: 'short-lived-token',
        profile: { sub: 'subject-a' },
      },
      events: {
        addAccessTokenExpiring: vi.fn((listener) => {
          expiringListener = listener;
          return removeExpiring;
        }),
        addAccessTokenExpired: vi.fn((listener) => {
          expiredListener = listener;
          return removeExpired;
        }),
      },
      signinSilent,
      removeUser,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
    } as unknown as ReturnType<typeof useAuth>);

    const view = render(
      <AuthSessionBridge>
        <SessionProbe />
      </AuthSessionBridge>,
    );
    expect(screen.getByText('authenticated')).toBeVisible();

    await act(async () => expiringListener?.());
    expect(signinSilent).toHaveBeenCalledOnce();

    await act(async () => expiredListener?.());
    expect(removeUser).toHaveBeenCalledOnce();
    expect(window.sessionStorage.getItem(SESSION_EXPIRED_KEY)).toBe('true');

    view.unmount();
    expect(removeExpiring).toHaveBeenCalledOnce();
    expect(removeExpired).toHaveBeenCalledOnce();
  });
});
