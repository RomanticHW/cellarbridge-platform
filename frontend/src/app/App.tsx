import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp, ConfigProvider } from 'antd';
import { useState } from 'react';
import { RouterProvider } from 'react-router-dom';
import { AppErrorBoundary } from './AppErrorBoundary';
import { router } from './router';

const theme = {
  token: {
    colorPrimary: '#176b57',
    colorInfo: '#176b57',
    colorTextSecondary: '#535f68',
    colorTextDescription: '#535f68',
    borderRadius: 6,
    fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
  },
  components: {
    Descriptions: {
      labelColor: '#535f68',
    },
    Menu: {
      itemSelectedBg: '#d8eee7',
      itemSelectedColor: '#0c4f3e',
    },
  },
};

export function App() {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: false,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return (
    <AppErrorBoundary>
      <ConfigProvider theme={theme}>
        <AntApp>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </AntApp>
      </ConfigProvider>
    </AppErrorBoundary>
  );
}
