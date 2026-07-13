import { Flex, Skeleton, Typography } from 'antd';

interface LoadingStateProps {
  message?: string;
}

export function LoadingState({ message = 'Checking backend readiness…' }: LoadingStateProps) {
  return (
    <Flex vertical gap="small" role="status" aria-live="polite">
      <Typography.Text>{message}</Typography.Text>
      <Skeleton active paragraph={{ rows: 1 }} title={false} />
    </Flex>
  );
}
