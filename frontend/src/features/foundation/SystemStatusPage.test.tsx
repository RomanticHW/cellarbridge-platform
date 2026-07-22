import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SystemStatusPage } from './SystemStatusPage';

function renderPage(element: ReactElement = <SystemStatusPage />) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
}

describe('SystemStatusPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows a loading state while readiness is pending', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByRole('status')).toHaveTextContent('Checking backend readiness');
  });

  it('shows backend readiness and marks business modules as planned', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'UP' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const { container } = renderPage();

    expect(
      await screen.findByText('Reported by the backend readiness health group.'),
    ).toBeVisible();
    expect(screen.getAllByText('Planned')).toHaveLength(3);
    expect(
      screen.getByText(/Inventory reservation, and Fulfillment orchestration are available/),
    ).toBeVisible();
    expect((await axe.run(container)).violations).toEqual([]);
  });

  it('shows a recoverable error when readiness fails', async () => {
    const user = userEvent.setup();
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ status: 'UP' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

    renderPage();

    expect(await screen.findByText('Backend readiness is unavailable')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Check again' }));
    expect(
      await screen.findByText('Reported by the backend readiness health group.'),
    ).toBeVisible();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('rejects malformed readiness responses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ state: 'UP' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    renderPage();

    expect(await screen.findByText('Backend readiness is unavailable')).toBeVisible();
  });
});
