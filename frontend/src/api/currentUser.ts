import { useQuery } from '@tanstack/react-query';
import type { components } from './generated/schema';
import { apiClient } from './client';

export type CurrentUser = components['schemas']['CurrentUser'];

export class ApiAccessError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(`API access failed with ${status} (${code})`);
  }
}

export async function getCurrentUser(
  accessToken: string,
  signal?: AbortSignal,
): Promise<CurrentUser> {
  const { data, error, response } = await apiClient.GET('/me', {
    signal,
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (data !== undefined) {
    return data;
  }
  throw new ApiAccessError(response.status, error?.code ?? 'INTERNAL_ERROR');
}

export function useCurrentUser(accessToken?: string, sessionIdentity?: string) {
  return useQuery({
    queryKey: ['identity-access', 'current-user', sessionIdentity],
    queryFn: ({ signal }) => {
      if (accessToken === undefined) {
        throw new ApiAccessError(401, 'AUTHENTICATION_REQUIRED');
      }
      return getCurrentUser(accessToken, signal);
    },
    enabled: accessToken !== undefined && sessionIdentity !== undefined,
    staleTime: 30_000,
  });
}
