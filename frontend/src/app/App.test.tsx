import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { AppErrorBoundary } from './AppErrorBoundary';
import { router } from './router';

function BrokenView(): never {
  throw new Error('render failure');
}

describe('application shell', () => {
  beforeEach(async () => {
    vi.restoreAllMocks();
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'UP' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    await router.navigate('/app');
  });

  it('renders the system status route and navigation', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'System status' })).toBeVisible();
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
    await user.click(screen.getByText('System status', { selector: '.ant-menu-title-content' }));
    expect(router.state.location.pathname).toBe('/app');
  });

  it('renders the not-found route', async () => {
    await router.navigate('/not-part-of-foundation');
    render(<App />);

    expect(await screen.findByText('Page not found')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Return to system status' })).toHaveAttribute(
      'href',
      '/app',
    );
  });

  it('contains render failures without exposing error details', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <AppErrorBoundary>
        <BrokenView />
      </AppErrorBoundary>,
    );

    expect(screen.getByText('The operations console could not be displayed')).toBeVisible();
    expect(screen.queryByText('render failure')).not.toBeInTheDocument();
    expect(consoleError).toHaveBeenCalled();
  });
});
