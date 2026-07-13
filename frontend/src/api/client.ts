import createClient from 'openapi-fetch';
import type { paths } from './generated/schema';

const apiBaseUrl =
  import.meta.env.VITE_API_BASE_URL ?? new URL('/api/v1', window.location.origin).toString();

export const apiClient = createClient<paths>({
  baseUrl: apiBaseUrl,
  headers: { Accept: 'application/json' },
  fetch: (request) => globalThis.fetch(request),
});
