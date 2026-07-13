import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';

export function ForbiddenPage() {
  const navigate = useNavigate();

  return (
    <main className="forbidden-page">
      <Result
        status="403"
        title="Access forbidden"
        subTitle="Your identity is valid, but the current tenant or permission policy does not allow this action."
        extra={
          <Button type="primary" onClick={() => navigate('/app')}>
            Return to operations
          </Button>
        }
      />
    </main>
  );
}
