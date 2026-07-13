import { Alert, Button, Card, Flex, Typography } from 'antd';
import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { AUTH_RETURN_TO_KEY, SESSION_EXPIRED_KEY } from './authConfig';
import { useAuthSession } from './authSession';

interface LoginLocationState {
  returnTo?: string;
}

export function LoginPage() {
  const session = useAuthSession();
  const location = useLocation();
  const [sessionExpired] = useState(() => {
    const expired = window.sessionStorage.getItem(SESSION_EXPIRED_KEY) === 'true';
    window.sessionStorage.removeItem(SESSION_EXPIRED_KEY);
    return expired;
  });
  const state = location.state as LoginLocationState | null;
  const returnTo =
    state?.returnTo === '/app' || state?.returnTo?.startsWith('/app/') ? state.returnTo : '/app';

  if (session.isAuthenticated) {
    return <Navigate to={returnTo} replace />;
  }

  const startSignIn = () => {
    window.sessionStorage.setItem(AUTH_RETURN_TO_KEY, returnTo);
    void session.signIn();
  };

  return (
    <main className="auth-page">
      <Card className="auth-card">
        <Flex vertical gap="large">
          <div>
            <Typography.Text className="auth-brand">CellarBridge</Typography.Text>
            <Typography.Title level={1}>Sign in to Operations</Typography.Title>
            <Typography.Paragraph type="secondary">
              Authentication uses the local OIDC provider with Authorization Code and PKCE.
            </Typography.Paragraph>
          </div>
          {sessionExpired && (
            <Alert
              type="warning"
              showIcon
              title="Session expired"
              description="Sign in again to continue. No access token was retained."
            />
          )}
          {session.errorMessage !== undefined && (
            <Alert
              type="error"
              showIcon
              title="Sign-in unavailable"
              description={session.errorMessage}
            />
          )}
          <Button type="primary" size="large" onClick={startSignIn} loading={session.isLoading}>
            Continue with OIDC
          </Button>
        </Flex>
      </Card>
    </main>
  );
}
