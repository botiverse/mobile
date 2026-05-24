import webSocket from '@ohos.net.webSocket';
import util from '@ohos.util';
import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';

const REALTIME_EVENT_NAME = 'slockRealtimeEvent';
const DEFAULT_SOCKET_URL = 'https://api.slock.ai';
const SOCKET_IO_PATH = '/socket.io/';
const ENGINE_IO_OPEN = '0';
const ENGINE_IO_PING = '2';
const ENGINE_IO_PONG = '3';
const SOCKET_IO_CONNECT = '40';
const SOCKET_IO_EVENT = '42';
const SOCKET_IO_CONNECT_ERROR = '44';

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
  private socket?: webSocket.WebSocket;
  private connectParams?: RealtimeConnectParams;
  private connected: boolean = false;
  private reconnectTimer?: number;
  private reconnectAttempt: number = 0;
  private readonly decoder: util.TextDecoder = new util.TextDecoder('utf-8');

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
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

  private connect(params: RealtimeConnectParams): void {
    if (params.serverId.length === 0 || params.accessToken.length === 0) {
      console.warn('[SlockOhosRealtime] connect skipped: missing server/token');
      return;
    }
    const previous = this.connectParams;
    this.connectParams = params;
    if (this.connected && previous?.serverId === params.serverId && previous?.accessToken === params.accessToken) {
      return;
    }
    this.disconnect('reconnect');
    this.openSocket(params);
  }

  private openSocket(params: RealtimeConnectParams): void {
    const socket = webSocket.createWebSocket();
    this.socket = socket;
    this.connected = false;

    socket.on('open', () => {
      console.info(`[SlockOhosRealtime] websocket_open serverId=${params.serverId}`);
    });
    socket.on('message', (_error, data) => {
      this.handleFrame(this.frameToString(data), params);
    });
    socket.on('close', (_error, closeResult) => {
      this.connected = false;
      console.info(`[SlockOhosRealtime] websocket_close code=${closeResult?.code ?? 0} reason=${closeResult?.reason ?? ''}`);
      this.scheduleReconnect('close');
    });
    socket.on('error', (error) => {
      this.connected = false;
      console.error(`[SlockOhosRealtime] websocket_error ${error?.message ?? ''}`);
      this.scheduleReconnect('error');
    });

    socket.connect(this.socketIoWebSocketUrl(params.socketUrl), {
      header: {
        'Authorization': `Bearer ${params.accessToken}`,
        'X-Server-Id': params.serverId,
      }
    }).catch((error: Error) => {
      console.error(`[SlockOhosRealtime] websocket_connect_failed ${error.message}`);
      this.scheduleReconnect('connect_failed');
    });
  }

  private disconnect(reason: string): void {
    if (this.reconnectTimer !== undefined) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
    const socket = this.socket;
    this.socket = undefined;
    this.connected = false;
    if (socket !== undefined) {
      socket.close({ code: 1000, reason: reason }).catch(() => {});
    }
  }

  private scheduleReconnect(reason: string): void {
    const params = this.connectParams;
    if (params === undefined || this.reconnectTimer !== undefined) {
      return;
    }
    const delayMillis = Math.min(30000, 1000 * Math.pow(2, Math.min(this.reconnectAttempt, 5)));
    this.reconnectAttempt += 1;
    console.info(`[SlockOhosRealtime] reconnect_scheduled reason=${reason} delay=${delayMillis}`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.openSocket(params);
    }, delayMillis);
  }

  private handleFrame(frame: string, params: RealtimeConnectParams): void {
    if (frame.length === 0) {
      return;
    }
    if (frame.startsWith(ENGINE_IO_OPEN)) {
      this.sendSocketIoConnect(params);
      return;
    }
    if (frame === ENGINE_IO_PING) {
      this.sendRaw(ENGINE_IO_PONG);
      return;
    }
    if (frame.startsWith(SOCKET_IO_CONNECT)) {
      this.connected = true;
      this.reconnectAttempt = 0;
      if (params.lastSeq > 0) {
        this.syncResume(params.lastSeq);
      }
      return;
    }
    if (frame.startsWith(SOCKET_IO_CONNECT_ERROR)) {
      console.warn(`[SlockOhosRealtime] socket_connect_error ${frame.substring(SOCKET_IO_CONNECT_ERROR.length)}`);
      return;
    }
    if (frame.startsWith(SOCKET_IO_EVENT)) {
      this.handleSocketIoEvent(frame.substring(SOCKET_IO_EVENT.length), params.serverId);
    }
  }

  private sendSocketIoConnect(params: RealtimeConnectParams): void {
    this.sendRaw(`${SOCKET_IO_CONNECT}${JSON.stringify({
      token: params.accessToken,
      serverId: params.serverId
    })}`);
  }

  private handleSocketIoEvent(payload: string, serverId: string): void {
    try {
      const values = JSON.parse(payload) as Array<Object>;
      const eventName = values[0] as string;
      if (WIRE_EVENTS.indexOf(eventName) < 0) {
        return;
      }
      const rawPayload = JSON.stringify(values[1] ?? {});
      this.controller?.sendEvent(REALTIME_EVENT_NAME, {
        serverId: serverId,
        eventName: eventName,
        rawPayload: rawPayload,
      });
    } catch (error) {
      console.warn(`[SlockOhosRealtime] event_decode_failed ${error}`);
    }
  }

  private joinChannel(channelId: string): void {
    if (channelId.length === 0 || !this.connected) {
      return;
    }
    this.emit('join:channel', { channelId: channelId });
  }

  private syncResume(lastSeq: number): void {
    if (lastSeq <= 0 || !this.connected) {
      return;
    }
    this.emit('sync:resume', { afterSeq: lastSeq });
  }

  private emit(eventName: string, payload: KRRecord): void {
    this.sendRaw(`${SOCKET_IO_EVENT}${JSON.stringify([eventName, payload])}`);
  }

  private sendRaw(text: string): void {
    this.socket?.send(text).catch((error: Error) => {
      console.warn(`[SlockOhosRealtime] send_failed ${error.message}`);
    });
  }

  private frameToString(data: string | ArrayBuffer): string {
    if (typeof data === 'string') {
      return data;
    }
    return this.decoder.decodeWithStream(new Uint8Array(data));
  }

  private socketIoWebSocketUrl(socketUrl: string): string {
    const base = socketUrl.length > 0 ? socketUrl : DEFAULT_SOCKET_URL;
    const withoutTrailingSlash = base.endsWith('/') ? base.substring(0, base.length - 1) : base;
    const wsScheme =
      withoutTrailingSlash.startsWith('https://')
        ? `wss://${withoutTrailingSlash.substring('https://'.length)}`
        : withoutTrailingSlash.startsWith('http://')
          ? `ws://${withoutTrailingSlash.substring('http://'.length)}`
          : withoutTrailingSlash;
    return `${wsScheme}${SOCKET_IO_PATH}?EIO=4&transport=websocket`;
  }
}

const slockOhosRealtimeBridge = new SlockOhosRealtimeBridge();

export default slockOhosRealtimeBridge;
