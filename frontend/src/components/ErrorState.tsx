import { Alert, Button, Flex } from 'antd';

interface ErrorStateProps {
  onRetry: () => void;
}

export function ErrorState({ onRetry }: ErrorStateProps) {
  return (
    <Flex vertical gap="middle">
      <Alert
        type="error"
        showIcon
        message="Backend readiness is unavailable"
        description="The operations console is running, but it cannot reach the backend readiness endpoint."
      />
      <Button onClick={onRetry}>Check again</Button>
    </Flex>
  );
}
