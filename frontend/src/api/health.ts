export interface HealthResponse {
  status: string;
}

const backendOrigin = import.meta.env.VITE_BACKEND_ORIGIN ?? '';

export async function getBackendReadiness(signal?: AbortSignal): Promise<HealthResponse> {
  const response = await fetch(`${backendOrigin}/actuator/health/readiness`, {
    signal,
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(`Readiness endpoint returned ${response.status}`);
  }

  const body: unknown = await response.json();
  if (
    typeof body !== 'object' ||
    body === null ||
    !('status' in body) ||
    typeof body.status !== 'string'
  ) {
    throw new Error('Readiness endpoint returned an unexpected response');
  }

  return { status: body.status };
}
