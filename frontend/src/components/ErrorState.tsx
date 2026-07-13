import { Alert, Button, Flex } from 'antd';

interface ErrorStateProps {
  onRetry: () => void;
  title?: string;
  description?: string;
  actionLabel?: string;
}

export function ErrorState({
  onRetry,
  title = 'Backend readiness is unavailable',
  description = 'The operations console is running, but it cannot reach the backend readiness endpoint.',
  actionLabel = 'Check again',
}: ErrorStateProps) {
  return (
    <Flex vertical gap="middle">
      <Alert type="error" showIcon title={title} description={description} />
      <Button onClick={onRetry}>{actionLabel}</Button>
    </Flex>
  );
}
