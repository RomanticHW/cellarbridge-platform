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
const PartnerListPage = lazy(() =>
  import('../features/partners/PartnerListPage').then((module) => ({
    default: module.PartnerListPage,
  })),
);
const PartnerEditorPage = lazy(() =>
  import('../features/partners/PartnerEditorPage').then((module) => ({
    default: module.PartnerEditorPage,
  })),
);
const PartnerDetailPage = lazy(() =>
  import('../features/partners/PartnerDetailPage').then((module) => ({
    default: module.PartnerDetailPage,
  })),
);
const CatalogSearchPage = lazy(() =>
  import('../features/catalog/CatalogSearchPage').then((module) => ({
    default: module.CatalogSearchPage,
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

function PartnerRoute({ page }: { page: 'list' | 'editor' | 'detail' }) {
  const Page =
    page === 'list' ? PartnerListPage : page === 'editor' ? PartnerEditorPage : PartnerDetailPage;
  return (
    <Suspense
      fallback={
        <Flex role="status" aria-label="Loading partner workspace" justify="center">
          <Spin size="large" />
        </Flex>
      }
    >
      <Page />
    </Suspense>
  );
}

function CatalogRoute() {
  return (
    <Suspense
      fallback={
        <Flex role="status" aria-label="Loading catalog and supply" justify="center">
          <Spin size="large" />
        </Flex>
      }
    >
      <CatalogSearchPage />
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
          { path: 'partners', element: <PartnerRoute page="list" /> },
          { path: 'partners/new', element: <PartnerRoute page="editor" /> },
          { path: 'partners/:partnerId/edit', element: <PartnerRoute page="editor" /> },
          { path: 'partners/:partnerId', element: <PartnerRoute page="detail" /> },
          { path: 'catalog', element: <CatalogRoute /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]);
