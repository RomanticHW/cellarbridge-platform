import { Button, Layout, Menu, Space, Tag, Typography, type MenuProps } from 'antd';
import { useEffect } from 'react';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { ApiAccessError, useCurrentUser } from '../api/currentUser';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { plannedModules } from '../features/foundation/plannedModules';
import { useAuthSession } from '../features/identity-access/authSession';

const { Header, Sider, Content, Footer } = Layout;

function createMenuItems(permissions: ReadonlyArray<string>): MenuProps['items'] {
  const visibleModules = plannedModules.filter((module) =>
    permissions.includes(module.requiredPermission),
  );
  const plannedItems: MenuProps['items'] = visibleModules.map((module) => ({
    key: `planned-${module.key}`,
    disabled: true,
    label: (
      <span className="planned-navigation-item">
        <span>{module.label}</span>
        <Tag>Planned</Tag>
      </span>
    ),
  }));

  return [
    { key: '/app', label: 'System status' },
    { key: '/app/profile', label: 'Identity & access' },
    ...(plannedItems.length === 0
      ? []
      : [
          {
            type: 'group' as const,
            label: 'Permitted modules',
            children: plannedItems,
          },
        ]),
  ];
}

export function AppShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const session = useAuthSession();
  const { clearSession } = session;
  const currentUser = useCurrentUser(session.accessToken, session.sessionIdentity);
  const accessError = currentUser.error instanceof ApiAccessError ? currentUser.error : undefined;

  useEffect(() => {
    if (accessError?.status === 401) {
      void clearSession();
    }
  }, [accessError?.status, clearSession]);

  if (currentUser.isPending) {
    return (
      <main className="session-state-page">
        <LoadingState message="Resolving tenant and permissions…" />
      </main>
    );
  }
  if (accessError?.status === 403) {
    return <Navigate to="/forbidden" replace />;
  }
  if (accessError?.status === 401) {
    return <Navigate to="/login" replace />;
  }
  if (currentUser.isError) {
    return (
      <main className="session-state-page">
        <ErrorState
          title="Access profile is unavailable"
          description="The verified session could not be resolved to a local tenant and permission profile."
          actionLabel="Try again"
          onRetry={() => void currentUser.refetch()}
        />
      </main>
    );
  }

  const menuItems = createMenuItems(currentUser.data.permissions);

  return (
    <Layout className="app-shell">
      <Header className="app-header">
        <div>
          <Typography.Text className="brand">CellarBridge</Typography.Text>
          <Typography.Text className="brand-subtitle">Operations</Typography.Text>
        </div>
        <Space className="identity-summary" size="middle">
          <span>
            <Typography.Text className="identity-name">
              {currentUser.data.displayName}
            </Typography.Text>
            <Typography.Text className="identity-tenant">
              {currentUser.data.tenant.name}
            </Typography.Text>
          </span>
          <Button ghost onClick={() => void session.signOut()}>
            Sign out
          </Button>
        </Space>
      </Header>
      <Layout>
        <Sider breakpoint="lg" collapsedWidth="0" width={272} className="app-sidebar">
          <nav aria-label="Main navigation">
            <Menu
              mode="inline"
              selectedKeys={[location.pathname]}
              onClick={({ key }) => navigate(key)}
              items={menuItems}
            />
          </nav>
        </Sider>
        <Layout>
          <Content className="app-content">
            <Outlet />
          </Content>
          <Footer className="app-footer">CellarBridge secured operations</Footer>
        </Layout>
      </Layout>
    </Layout>
  );
}
