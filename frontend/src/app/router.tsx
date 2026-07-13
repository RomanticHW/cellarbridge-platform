import { Flex, Spin } from 'antd';
import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { NotFoundPage } from '../components/NotFoundPage';
import { ForbiddenPage } from '../features/identity-access/ForbiddenPage';
import { LoginPage } from '../features/identity-access/LoginPage';
import { RequireAuthentication } from '../features/identity-access/RequireAuthentication';
import { AppShell } from './AppShell';

const SystemStatusPage = lazy(() =>
  import('../features/foundation/SystemStatusPage').then((module) => ({
    default: module.SystemStatusPage,
  })),
);
const ProfilePage = lazy(() =>
  import('../features/identity-access/ProfilePage').then((module) => ({
    default: module.ProfilePage,
  })),
);

function FoundationRoute() {
  return (
    <Suspense
      fallback={
        <Flex role="status" aria-label="Loading system status" justify="center">
          <Spin size="large" />
        </Flex>
      }
    >
      <SystemStatusPage />
    </Suspense>
  );
}

function ProfileRoute() {
  return (
    <Suspense
      fallback={
        <Flex role="status" aria-label="Loading access profile" justify="center">
          <Spin size="large" />
        </Flex>
      }
    >
      <ProfilePage />
    </Suspense>
  );
}

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/app" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/forbidden', element: <ForbiddenPage /> },
  {
    element: <RequireAuthentication />,
    children: [
      {
        path: '/app',
        element: <AppShell />,
        children: [
          { index: true, element: <FoundationRoute /> },
          { path: 'profile', element: <ProfileRoute /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]);
