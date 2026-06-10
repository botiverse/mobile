import { KRAny, KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import { OhosSocketIoClient } from './OhosSocketIoClient';
import { SLOCK_SOCKET_BASE_URL } from './SlockOhosApiConfig';

const REALTIME_EVENT_NAME = 'slockRealtimeEvent';
const DEFAULT_SOCKET_URL = SLOCK_SOCKET_BASE_URL;
const HEARTBEAT_SILENCE_MILLIS = 90_000;
const INBOUND_SILENCE_MILLIS = 120_000;
const WATCHDOG_INTERVAL_MILLIS = 30_000;

const WIRE_EVENTS: Array<string> = [
  'message:new',
  'dm:new',
  'message:updated',
  'thread:updated',
  'channel:updated',
  'channel:members-updated',
  'task:created',
  'task:updated',
  'task:deleted',
  'agent:activity',
  'agent:created',
  'agent:deleted',
  'machine:status',
  'machine:capabilities',
  'machine:updated',
  'server:member-added',
  'server:member-updated',
  'server:member:left',
  'server:member-removed',
  'server:membership-removed',
  'server:deleted',
  'server:plan-updated',
  'announcement:new',
  'reminder:fired',
  'reminder:scheduled',
  'reminder:canceled',
  'sync:resume:response',
  'heartbeat',
];

interface RealtimeConnectParams {
  serverId: string;
  accessToken: string;
  socketUrl: string;
  lastSeq: number;
}

export class SlockOhosRealtimeBridge {
  private controller?: KRNativeRenderController;
  private readonly socketIo: OhosSocketIoClient = new OhosSocketIoClient();
  private readonly joinedChannels: Set<string> = new Set();
  private connectParams?: RealtimeConnectParams;
  private watchdogTimer?: number;
  private lastHeartbeatAtMillis?: number;
  private lastInboundEventAtMillis?: number;

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
    this.socketIo.setListener({
      onConnected: () => {
        this.markInbound();
        this.startHeartbeatWatchdog();
        console.info('[SlockOhosRealtime] socket_io_connected');
        this.rejoinChannels('connect');
      },
      onDisconnected: (reason: string) => {
        this.stopHeartbeatWatchdog();
        console.info(`[SlockOhosRealtime] socket_io_disconnected reason=${reason}`);
      },
      onConnectError: (message: string) => {
        this.markInbound();
        console.warn(`[SlockOhosRealtime] socket_io_connect_error ${message}`);
      },
      onEvent: (eventName, payload) => {
        this.handleSocketIoEvent(eventName, payload);
      }
    });
  }

  handleCommand(params: KRRecord): void {
    const action = params['action'] as string;
    switch (action) {
      case 'connect':
        this.connect({
          serverId: (params['serverId'] as string) ?? '',
          accessToken: (params['accessToken'] as string) ?? '',
          socketUrl: (params['socketUrl'] as string) ?? DEFAULT_SOCKET_URL,
          lastSeq: Number(params['lastSeq'] ?? 0),
        });
        break;
      case 'disconnect':
        this.disconnect((params['reason'] as string) ?? 'shared_disconnect');
        break;
      case 'joinChannel':
        this.joinChannel((params['channelId'] as string) ?? '');
        break;
      case 'syncResume':
        this.syncResume(Number(params['lastSeq'] ?? 0));
        break;
      default:
        console.warn(`[SlockOhosRealtime] unsupported action=${action}`);
        break;
    }
  }

  onPageShow(): void {
    this.socketIo.resume();
  }

  onPageHide(): void {
    this.socketIo.suspend('background');
  }

  private connect(params: RealtimeConnectParams): void {
    if (params.serverId.length === 0 || params.accessToken.length === 0) {
      console.warn('[SlockOhosRealtime] connect skipped: missing server/token');
      return;
    }
    const previous = this.connectParams;
    if (previous !== undefined && previous.serverId !== params.serverId) {
      this.joinedChannels.clear();
    }
    this.connectParams = params;
    this.lastHeartbeatAtMillis = undefined;
    this.lastInboundEventAtMillis = undefined;
    if (this.socketIo.isConnected() && previous?.serverId === params.serverId && previous?.accessToken === params.accessToken) {
      return;
    }
    this.disconnect('reconnect');
    this.socketIo.connect({
      socketUrl: params.socketUrl.length > 0 ? params.socketUrl : DEFAULT_SOCKET_URL,
      auth: {
        token: params.accessToken,
        serverId: params.serverId,
      },
      headers: {
        'Authorization': `Bearer ${params.accessToken}`,
        'X-Server-Id': params.serverId,
      }
    });
  }

  private disconnect(reason: string): void {
    if (reason !== 'reconnect') {
      this.joinedChannels.clear();
      this.connectParams = undefined;
    }
    this.stopHeartbeatWatchdog();
    this.socketIo.disconnect(reason);
  }

  private handleSocketIoEvent(eventName: string, payload: KRAny): void {
    this.markInbound(eventName);
    if (eventName === 'rooms:joined') {
      this.onRoomsJoined();
      return;
    }
    if (WIRE_EVENTS.indexOf(eventName) < 0) {
      return;
    }
    if (eventName === 'heartbeat') {
      const heartbeat = payload as KRRecord;
      console.info(`[SlockOhosRealtime] app_heartbeat_received seq=${Number(heartbeat['seq'] ?? 0)}`);
    }
    const serverId = this.connectParams?.serverId ?? '';
    this.controller?.sendEvent(REALTIME_EVENT_NAME, {
      serverId: serverId,
      eventName: eventName,
      rawPayload: JSON.stringify(payload ?? {}),
    });
  }

  private onRoomsJoined(): void {
    const lastSeq = this.connectParams?.lastSeq ?? 0;
    console.info(`[SlockOhosRealtime] rooms_joined joinedRooms=${this.joinedChannels.size} lastSeq=${lastSeq}`);
    if (lastSeq > 0) {
      this.syncResume(lastSeq);
    }
  }

  private joinChannel(channelId: string): void {
    if (channelId.length === 0) {
      return;
    }
    this.joinedChannels.add(channelId);
    if (!this.socketIo.emit('join:channel', channelId)) {
      console.info('[SlockOhosRealtime] join_channel_deferred');
    }
  }

  private rejoinChannels(reason: string): void {
    this.joinedChannels.forEach((channelId) => {
      console.info(`[SlockOhosRealtime] rejoin_channel reason=${reason}`);
      this.socketIo.emit('join:channel', channelId);
    });
  }

  private syncResume(lastSeq: number): void {
    if (lastSeq <= 0) {
      return;
    }
    console.info(`[SlockOhosRealtime] sync_resume_emit lastSeq=${lastSeq}`);
    this.socketIo.emit('sync:resume', { lastSeq: lastSeq });
  }

  private markInbound(eventName?: string): void {
    const now = Date.now();
    this.lastInboundEventAtMillis = now;
    if (eventName === 'heartbeat') {
      this.lastHeartbeatAtMillis = now;
    }
  }

  private startHeartbeatWatchdog(): void {
    if (this.watchdogTimer !== undefined) {
      return;
    }
    this.watchdogTimer = setInterval(() => {
      this.checkHeartbeatSilence();
    }, WATCHDOG_INTERVAL_MILLIS);
  }

  private stopHeartbeatWatchdog(): void {
    if (this.watchdogTimer !== undefined) {
      clearInterval(this.watchdogTimer);
      this.watchdogTimer = undefined;
    }
  }

  private checkHeartbeatSilence(): void {
    if (!this.socketIo.isConnected()) {
      return;
    }
    const heartbeatAt = this.lastHeartbeatAtMillis;
    const inboundAt = this.lastInboundEventAtMillis;
    if (heartbeatAt === undefined || inboundAt === undefined) {
      return;
    }
    const now = Date.now();
    if (now - heartbeatAt <= HEARTBEAT_SILENCE_MILLIS || now - inboundAt <= INBOUND_SILENCE_MILLIS) {
      return;
    }
    console.warn(`[SlockOhosRealtime] heartbeat_silence_reconnect lastHeartbeatAt=${heartbeatAt} lastInboundAt=${inboundAt}`);
    const params = this.connectParams;
    if (params === undefined) {
      return;
    }
    this.lastHeartbeatAtMillis = undefined;
    this.lastInboundEventAtMillis = now;
    this.socketIo.connect({
      socketUrl: params.socketUrl.length > 0 ? params.socketUrl : DEFAULT_SOCKET_URL,
      auth: {
        token: params.accessToken,
        serverId: params.serverId,
      },
      headers: {
        'Authorization': `Bearer ${params.accessToken}`,
        'X-Server-Id': params.serverId,
      }
    });
  }
}

const slockOhosRealtimeBridge = new SlockOhosRealtimeBridge();

export default slockOhosRealtimeBridge;
