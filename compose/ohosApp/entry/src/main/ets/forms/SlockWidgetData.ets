import { common } from '@kit.AbilityKit';
import { http } from '@kit.NetworkKit';
import util from '@ohos.util';
import { MMKV } from '@tencent/mmkv';
import { SLOCK_API_BASE_URL } from '../kuikly/SlockOhosApiConfig';
import { SlockOhosApiEnvironmentStore } from '../kuikly/SlockOhosApiEnvironmentStore';

const AUTH_SESSION_MMKV_NAMESPACE = 'slockAuthSession';
const AUTH_SESSION_MMKV_STORAGE_KEY = 'slockAuthSession:session';
const REQUEST_TIMEOUT_MS = 8000;

export const FORM_ACTIVITY_UNREAD = 'activityUnread';
export const FORM_AGENT_RUNTIME = 'agentRuntimeStatus';

interface StoredAuthServer {
  id: string;
  name?: string;
  slug?: string;
}

interface StoredAuthSession {
  accessToken: string;
  selectedServerId?: string;
  servers?: StoredAuthServer[];
}

interface ServerUnreadSummary {
  serverId?: string;
  unreadCount?: number;
}

interface AgentWire {
  id?: string;
  name?: string;
  displayName?: string;
  status?: string;
  activity?: string;
  activityDetail?: string;
  runtimeStatus?: string;
  running?: boolean;
}

interface AgentListWire {
  agents?: AgentWire[];
  items?: AgentWire[];
  results?: AgentWire[];
  data?: AgentWire[] | AgentListWire;
}

export interface SlockWidgetSnapshot {
  kind: string;
  title: string;
  countText: string;
  primaryLabel: string;
  secondaryLabel: string;
  accentLabel: string;
  stateLabel: string;
  footerLabel: string;
  updatedAtLabel: string;
  empty: boolean;
}

export function fallbackActivityUnreadSnapshot(
  reason: string = 'Open Raft to sync',
  stateLabel: string = 'Needs app session'
): SlockWidgetSnapshot {
  return {
    kind: FORM_ACTIVITY_UNREAD,
    title: 'Activity',
    countText: '--',
    primaryLabel: 'Unread',
    secondaryLabel: reason,
    accentLabel: 'RAFT',
    stateLabel,
    footerLabel: 'Tap to open',
    updatedAtLabel: nowLabel(),
    empty: true
  };
}

export function fallbackAgentRuntimeSnapshot(
  reason: string = 'Open Raft to sync',
  stateLabel: string = 'Needs app session'
): SlockWidgetSnapshot {
  return {
    kind: FORM_AGENT_RUNTIME,
    title: 'Live Agents',
    countText: '--',
    primaryLabel: 'Running',
    secondaryLabel: reason,
    accentLabel: 'LIVE',
    stateLabel,
    footerLabel: 'Tap to open',
    updatedAtLabel: nowLabel(),
    empty: true
  };
}

export async function loadActivityUnreadSnapshot(context: common.Context): Promise<SlockWidgetSnapshot> {
  const session = await readStoredAuthSession(context);
  if (session === null || session.accessToken.length === 0) {
    return fallbackActivityUnreadSnapshot();
  }
  const server = currentServer(session);
  if (server === null) {
    return fallbackActivityUnreadSnapshot('Select a server');
  }
  const summaries = await requestJson<ServerUnreadSummary[]>(session, server.id, 'servers/unread-summary', context);
  const match = summaries.find((summary: ServerUnreadSummary) => summary.serverId === server.id);
  const unread = Math.max(0, Number(match?.unreadCount ?? 0));
  return {
    kind: FORM_ACTIVITY_UNREAD,
    title: 'Activity',
    countText: unread.toString(),
    primaryLabel: 'Unread',
    secondaryLabel: serverLabel(server),
    accentLabel: unread > 0 ? 'NEW' : 'CLEAR',
    stateLabel: unread > 0 ? 'Needs attention' : 'All caught up',
    footerLabel: 'Updated',
    updatedAtLabel: nowLabel(),
    empty: unread === 0
  };
}

export async function loadAgentRuntimeSnapshot(context: common.Context): Promise<SlockWidgetSnapshot> {
  const session = await readStoredAuthSession(context);
  if (session === null || session.accessToken.length === 0) {
    return fallbackAgentRuntimeSnapshot();
  }
  const server = currentServer(session);
  if (server === null) {
    return fallbackAgentRuntimeSnapshot('Select a server');
  }
  const agents = parseAgentList(await requestJson<AgentWire[] | AgentListWire>(session, server.id, 'agents', context));
  const running = agents.filter((agent: AgentWire) => isAgentRunning(agent));
  const total = agents.length;
  const headline = runningHeadline(running);
  return {
    kind: FORM_AGENT_RUNTIME,
    title: 'Live Agents',
    countText: running.length.toString(),
    primaryLabel: 'Running',
    secondaryLabel: total > 0 ? `${headline} / ${total} total` : serverLabel(server),
    accentLabel: running.length > 0 ? 'LIVE' : 'IDLE',
    stateLabel: running.length > 0 ? 'Runtime active' : 'All idle',
    footerLabel: 'Updated',
    updatedAtLabel: nowLabel(),
    empty: running.length === 0
  };
}

function parseAgentList(wire: AgentWire[] | AgentListWire): AgentWire[] {
  if (Array.isArray(wire)) {
    return wire;
  }
  const data = wire.data;
  if (Array.isArray(data)) {
    return data;
  }
  if (data !== undefined && !Array.isArray(data)) {
    const nested = parseAgentList(data);
    if (nested.length > 0) {
      return nested;
    }
  }
  return wire.agents ?? wire.items ?? wire.results ?? [];
}

export function snapshotRecord(snapshot: SlockWidgetSnapshot): SlockWidgetSnapshot {
  return {
    kind: snapshot.kind,
    title: snapshot.title,
    countText: snapshot.countText,
    primaryLabel: snapshot.primaryLabel,
    secondaryLabel: snapshot.secondaryLabel,
    accentLabel: snapshot.accentLabel,
    stateLabel: snapshot.stateLabel,
    footerLabel: snapshot.footerLabel,
    updatedAtLabel: snapshot.updatedAtLabel,
    empty: snapshot.empty
  };
}

async function readStoredAuthSession(context: common.Context): Promise<StoredAuthSession | null> {
  try {
    try {
      MMKV.initialize(context.getApplicationContext());
    } catch (_error) {
      // MMKV may already be initialized by the app process.
    }
    const store = MMKV.mmkvWithID(AUTH_SESSION_MMKV_NAMESPACE);
    const encoded = store.decodeString(AUTH_SESSION_MMKV_STORAGE_KEY);
    if (!encoded || encoded.length === 0) {
      return null;
    }
    return JSON.parse(encoded) as StoredAuthSession;
  } catch (_error) {
    return null;
  }
}

async function requestJson<T>(session: StoredAuthSession, serverId: string, path: string, context: common.Context): Promise<T> {
  const client = http.createHttp();
  try {
    const response = await client.request(SlockOhosApiEnvironmentStore.rewriteApiUrlForContext(`${SLOCK_API_BASE_URL}${path}`, context), {
      method: http.RequestMethod.GET,
      header: {
        'Authorization': `Bearer ${session.accessToken}`,
        'X-Server-Id': serverId,
        'Accept': 'application/json'
      },
      connectTimeout: REQUEST_TIMEOUT_MS,
      readTimeout: REQUEST_TIMEOUT_MS
    });
    const code = Number(response.responseCode ?? 0);
    if (code < 200 || code >= 300) {
      throw new Error(`widget_request_failed code=${code}`);
    }
    const body = httpResultToString(response.result);
    return JSON.parse(body) as T;
  } finally {
    client.destroy();
  }
}

function currentServer(session: StoredAuthSession): StoredAuthServer | null {
  const servers = session.servers ?? [];
  if (servers.length === 0) {
    return null;
  }
  const selected = session.selectedServerId;
  return servers.find((server: StoredAuthServer) => server.id === selected) ?? servers[0];
}

function isAgentRunning(agent: AgentWire): boolean {
  if (agent.running === true) {
    return true;
  }
  const activity = `${agent.activity ?? agent.runtimeStatus ?? ''}`.toLowerCase();
  if (activity.length > 0 && activity !== 'offline' && activity !== 'idle') {
    return true;
  }
  const status = `${agent.status ?? ''}`.toLowerCase();
  return status === 'running' || status === 'active' || status === 'busy' || status === 'working';
}

function serverLabel(server: StoredAuthServer): string {
  return server.name || server.slug || 'Current server';
}

function runningHeadline(running: AgentWire[]): string {
  if (running.length === 0) {
    return 'No active agents';
  }
  const names = running.slice(0, 2)
    .map((agent: AgentWire) => agent.displayName || agent.name || 'Agent')
    .join(', ');
  const remaining = running.length - 2;
  return remaining > 0 ? `${names} +${remaining}` : names;
}

function nowLabel(): string {
  const date = new Date();
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

function pad2(value: number): string {
  return value < 10 ? `0${value}` : `${value}`;
}

function httpResultToString(result: string | ArrayBuffer | Object): string {
  if (typeof result === 'string') {
    return result;
  }
  if (result instanceof ArrayBuffer) {
    const decoder = new util.TextDecoder('utf-8');
    return decoder.decodeWithStream(new Uint8Array(result));
  }
  return JSON.stringify(result);
}
