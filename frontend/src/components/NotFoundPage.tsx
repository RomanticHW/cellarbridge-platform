import { Button, Result } from 'antd';
import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <main className="not-found-page">
      <Result
        status="404"
        title="Page not found"
        subTitle="This route is not part of the available foundation."
        extra={
          <Link to="/app">
            <Button type="primary">Return to system status</Button>
          </Link>
        }
      />
    </main>
  );
}
