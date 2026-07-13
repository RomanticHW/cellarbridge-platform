import { Flex, Spin } from 'antd';
import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { NotFoundPage } from '../components/NotFoundPage';
import { AppShell } from './AppShell';

const SystemStatusPage = lazy(() =>
  import('../features/foundation/SystemStatusPage').then((module) => ({
    default: module.SystemStatusPage,
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

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/app" replace /> },
  {
    path: '/app',
    element: <AppShell />,
    children: [{ index: true, element: <FoundationRoute /> }],
  },
  { path: '*', element: <NotFoundPage /> },
]);
