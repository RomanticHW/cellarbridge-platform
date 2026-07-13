import { Alert, Button, Flex } from 'antd';
import { Component, type ErrorInfo, type PropsWithChildren } from 'react';

type Props = PropsWithChildren;

interface State {
  failed: boolean;
}

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(
      'The application shell failed to render.',
      error.name,
      Boolean(info.componentStack),
    );
  }

  render() {
    if (this.state.failed) {
      return (
        <main className="fatal-error">
          <Flex vertical gap="middle">
            <Alert
              type="error"
              showIcon
              message="The operations console could not be displayed"
              description="Reload the page. If the problem continues, share the time of the failure with support."
            />
            <Button type="primary" onClick={() => window.location.reload()}>
              Reload
            </Button>
          </Flex>
        </main>
      );
    }

    return this.props.children;
  }
}
