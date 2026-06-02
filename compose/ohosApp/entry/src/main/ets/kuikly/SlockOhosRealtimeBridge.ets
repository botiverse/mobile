import { KRAny, KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import { OhosSocketIoClient } from './OhosSocketIoClient';

const REALTIME_EVENT_NAME = 'slockRealtimeEvent';
const DEFAULT_SOCKET_URL = 'https://api.slock.ai';

const WIRE_EVENTS: Array<string> = [
  'message:new',
  'dm:new',
  'message:updated',
  'thread:updated',
  'task:created',
  'task:updated',
  'task:deleted',
  'agent:activity',
  'server:member-added',
  'server:member-updated',
  'server:member:left',
  'server:member-removed',
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

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
    this.socketIo.setListener({
      onConnected: () => {
        console.info('[SlockOhosRealtime] socket_io_connected');
      },
      onDisconnected: (reason: string) => {
        console.info(`[SlockOhosRealtime] socket_io_disconnected reason=${reason}`);
      },
      onConnectError: (message: string) => {
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
    this.socketIo.disconnect(reason);
  }

  private handleSocketIoEvent(eventName: string, payload: KRAny): void {
    if (eventName === 'rooms:joined') {
      this.onRoomsJoined();
      return;
    }
    if (WIRE_EVENTS.indexOf(eventName) < 0) {
      return;
    }
    const serverId = this.connectParams?.serverId ?? '';
    this.controller?.sendEvent(REALTIME_EVENT_NAME, {
      serverId: serverId,
      eventName: eventName,
      rawPayload: JSON.stringify(payload ?? {}),
    });
  }

  private onRoomsJoined(): void {
    this.rejoinChannels('rooms_joined');
    const lastSeq = this.connectParams?.lastSeq ?? 0;
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
    this.socketIo.emit('sync:resume', { lastSeq: lastSeq });
  }
}

const slockOhosRealtimeBridge = new SlockOhosRealtimeBridge();

export default slockOhosRealtimeBridge;
