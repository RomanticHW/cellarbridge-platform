import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { LoadingState } from '../../components/LoadingState';
import { useAuthSession } from './authSession';

export function RequireAuthentication() {
  const session = useAuthSession();
  const location = useLocation();

  if (session.isLoading) {
    return <LoadingState message="Restoring the identity session…" />;
  }
  if (!session.isAuthenticated) {
    return <Navigate to="/login" replace state={{ returnTo: location.pathname }} />;
  }
  return <Outlet />;
}
