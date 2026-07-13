import { Card, Descriptions, Flex, Space, Tag, Typography } from 'antd';
import { useCurrentUser } from '../../api/currentUser';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from './authSession';

export function ProfilePage() {
  const session = useAuthSession();
  const currentUser = useCurrentUser(session.accessToken, session.sessionIdentity);

  if (currentUser.isPending) {
    return <LoadingState message="Loading access profile…" />;
  }
  if (!currentUser.isSuccess) {
    return null;
  }

  return (
    <section aria-labelledby="access-profile-title">
      <Flex vertical gap="large">
        <div>
          <Typography.Title id="access-profile-title" level={1}>
            Access profile
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            This profile is resolved from the verified OIDC subject and the local tenant mapping.
          </Typography.Paragraph>
        </div>
        <Card title="Current identity">
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="Display name">
              {currentUser.data.displayName}
            </Descriptions.Item>
            <Descriptions.Item label="Tenant">{currentUser.data.tenant.name}</Descriptions.Item>
            <Descriptions.Item label="Tenant status">
              <Tag color="green">{currentUser.data.tenant.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="Roles">
              <Space wrap>
                {currentUser.data.roles.map((role) => (
                  <Tag key={role}>{role}</Tag>
                ))}
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label="Permissions">
              <Space wrap>
                {currentUser.data.permissions.map((permission) => (
                  <Tag key={permission}>{permission}</Tag>
                ))}
              </Space>
            </Descriptions.Item>
          </Descriptions>
        </Card>
      </Flex>
    </section>
  );
}
