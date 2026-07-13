import { describe, expect, it } from 'vitest';
import { apiClient } from './client';

describe('generated API client boundary', () => {
  it('creates a typed OpenAPI client without invoking a business endpoint', () => {
    expect(apiClient).toBeDefined();
  });
});
