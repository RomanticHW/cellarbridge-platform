import { Flex, Skeleton, Typography } from 'antd';

export function LoadingState() {
  return (
    <Flex vertical gap="small" role="status" aria-live="polite">
      <Typography.Text>Checking backend readiness…</Typography.Text>
      <Skeleton active paragraph={{ rows: 1 }} title={false} />
    </Flex>
  );
}
