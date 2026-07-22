import type { components } from './generated/schema';
import { apiClient } from './client';

export type Dashboard = components['schemas']['Dashboard'];
export type AuditPage = components['schemas']['AuditPage'];
export type AuditEntry = components['schemas']['AuditEntry'];
export type TimelinePage = components['schemas']['TimelinePage'];
export type TimelineEntry = components['schemas']['TimelineEntry'];
export type WorkItemPage = components['schemas']['WorkItemPage'];
export type WorkItem = components['schemas']['WorkItem'];

type Problem = components['schemas']['Problem'];

export class ReportingApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

function apiError(response: Response, problem?: Problem) {
  return new ReportingApiError(
    response.status,
    problem?.code ?? 'INTERNAL_ERROR',
    problem?.detail ?? 'The reporting projection could not be loaded.',
  );
}

export async function getDashboard(
  accessToken: string,
  from: string,
  to: string,
  signal?: AbortSignal,
): Promise<Dashboard> {
  const { data, error, response } = await apiClient.GET('/dashboard', {
    headers: authorization(accessToken),
    params: { query: { from, to } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export interface AuditQuery {
  pageSize?: number;
  cursor?: string;
  subjectType?: string;
  subjectId?: string;
  correlationId?: string;
  actorId?: string;
  action?: string;
  from?: string;
  to?: string;
}

export async function listAuditEntries(
  accessToken: string,
  query: AuditQuery,
  signal?: AbortSignal,
): Promise<AuditPage> {
  const { data, error, response } = await apiClient.GET('/audit/entries', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export async function getTimeline(
  accessToken: string,
  subjectType: 'PARTNER' | 'QUOTATION' | 'TRADE_ORDER' | 'ORDER',
  subjectId: string,
  signal?: AbortSignal,
): Promise<TimelinePage> {
  const { data, error, response } = await apiClient.GET('/timeline', {
    headers: authorization(accessToken),
    params: { query: { subjectType, subjectId, pageSize: 50 } },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}

export interface WorkItemQuery {
  status?: Array<'OPEN' | 'CLAIMED' | 'COMPLETED' | 'CANCELLED'>;
  priority?: Array<'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'>;
  type?: string[];
  dueFrom?: string;
  dueTo?: string;
  subjectNumber?: string;
  scope?: 'personal' | 'team';
  pageSize?: number;
}

export async function listWorkItems(
  accessToken: string,
  query: WorkItemQuery,
  signal?: AbortSignal,
): Promise<WorkItemPage> {
  const { data, error, response } = await apiClient.GET('/work-items', {
    headers: authorization(accessToken),
    params: { query },
    signal,
  });
  if (data !== undefined) return data;
  throw apiError(response, error);
}
