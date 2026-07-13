import { Layout, Menu, Tag, Typography, type MenuProps } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { plannedModules } from '../features/foundation/plannedModules';

const { Header, Sider, Content, Footer } = Layout;
const moduleMenuItems: MenuProps['items'] = plannedModules.map((module) => ({
  key: `planned-${module.key}`,
  disabled: true,
  label: (
    <span className="planned-navigation-item">
      <span>{module.label}</span>
      <Tag>Planned</Tag>
    </span>
  ),
}));

const menuItems: MenuProps['items'] = [
  { key: '/app', label: 'System status' },
  {
    type: 'group',
    label: 'Business modules',
    children: moduleMenuItems,
  },
];

export function AppShell() {
  const navigate = useNavigate();
  const location = useLocation();

  return (
    <Layout className="app-shell">
      <Header className="app-header">
        <div>
          <Typography.Text className="brand">CellarBridge</Typography.Text>
          <Typography.Text className="brand-subtitle">Operations</Typography.Text>
        </div>
        <Tag color="blue">Foundation</Tag>
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
          <Footer className="app-footer">CellarBridge engineering foundation</Footer>
        </Layout>
      </Layout>
    </Layout>
  );
}
