import webSocket from '@ohos.net.webSocket';
import util from '@ohos.util';
import { KRAny, KRRecord } from '@kuikly-open/render';

const SOCKET_IO_PATH = '/socket.io/';
const ENGINE_IO_OPEN = '0';
const ENGINE_IO_CLOSE = '1';
const ENGINE_IO_PING = '2';
const ENGINE_IO_PONG = '3';
const SOCKET_IO_CONNECT = '40';
const SOCKET_IO_DISCONNECT = '41';
const SOCKET_IO_EVENT = '42';
const SOCKET_IO_CONNECT_ERROR = '44';

export interface OhosSocketIoConnectParams {
  socketUrl: string;
  auth: KRRecord;
  headers?: Record<string, string>;
}

export interface OhosSocketIoListener {
  onConnected?: () => void;
  onDisconnected?: (reason: string) => void;
  onEvent?: (eventName: string, payload: KRAny) => void;
  onConnectError?: (message: string) => void;
  /** Task #154: the curl stack could not open a session at all; the runtime falls back to webSocket. */
  onTransportUnavailable?: (reason: string) => void;
}

/**
 * Task #154: the realtime socket surface both stacks implement. OhosSocketIoClient is the
 * @ohos.net.webSocket client; OhosCurlSocketIoClient runs the connection over NetworkKMM's curl
 * Socket.IO client through KNOI.
 */
export interface OhosRealtimeSocket {
  setListener(listener: OhosSocketIoListener): void;
  isConnected(): boolean;
  connect(params: OhosSocketIoConnectParams): void;
  disconnect(reason: string): void;
  suspend(reason: string): void;
  resume(): void;
  emit(eventName: string, payload: KRAny): boolean;
}

export interface OhosSocketIoDecodedFrame {
  kind: string;
  eventName?: string;
  payload?: KRAny;
  message?: string;
  pingInterval?: number;
  pingTimeout?: number;
}

export function decodeOhosSocketIoFrame(frame: string): OhosSocketIoDecodedFrame {
  if (frame.length === 0) {
    return { kind: 'empty' };
  }
  if (frame.startsWith(ENGINE_IO_OPEN)) {
    const payload = frame.substring(ENGINE_IO_OPEN.length);
    try {
      const values = JSON.parse(payload) as KRRecord;
      return {
        kind: 'engine_open',
        pingInterval: Number(values['pingInterval'] ?? 0),
        pingTimeout: Number(values['pingTimeout'] ?? 0),
      };
    } catch (error) {
      return { kind: 'engine_open' };
    }
  }
  if (frame.startsWith(ENGINE_IO_CLOSE)) {
    return { kind: 'engine_close' };
  }
  if (frame === ENGINE_IO_PING) {
    return { kind: 'engine_ping' };
  }
  if (frame.startsWith(SOCKET_IO_CONNECT)) {
    return { kind: 'socket_connect' };
  }
  if (frame.startsWith(SOCKET_IO_DISCONNECT)) {
    return { kind: 'socket_disconnect' };
  }
  if (frame.startsWith(SOCKET_IO_CONNECT_ERROR)) {
    return {
      kind: 'socket_connect_error',
      message: frame.substring(SOCKET_IO_CONNECT_ERROR.length),
    };
  }
  if (frame.startsWith(SOCKET_IO_EVENT)) {
    try {
      const values = JSON.parse(frame.substring(SOCKET_IO_EVENT.length)) as Array<KRAny>;
      const eventName = values[0] as string;
      if (eventName === undefined || eventName.length === 0) {
        return { kind: 'invalid_event' };
      }
      return {
        kind: 'socket_event',
        eventName: eventName,
        payload: values.length > 1 ? values[1] : {},
      };
    } catch (error) {
      return { kind: 'invalid_event', message: `${error}` };
    }
  }
  return { kind: 'unknown' };
}

interface WebSocketConnectOptions {
  header: Record<string, string>;
}

interface WebSocketCloseOptions {
  code: number;
  reason: string;
}

export class OhosSocketIoClient implements OhosRealtimeSocket {
  private socket?: webSocket.WebSocket;
  private connectParams?: OhosSocketIoConnectParams;
  private listener: OhosSocketIoListener = {};
  private connected: boolean = false;
  private reconnectTimer?: number;
  private reconnectAttempt: number = 0;
  private manualDisconnect: boolean = false;
  private readonly decoder: util.TextDecoder = new util.TextDecoder('utf-8');

  setListener(listener: OhosSocketIoListener): void {
    this.listener = listener;
  }

  isConnected(): boolean {
    return this.connected;
  }

  connect(params: OhosSocketIoConnectParams): void {
    this.connectParams = params;
    this.manualDisconnect = false;
    this.clearReconnectTimer();
    this.disconnectSocket('reconnect');
    this.openSocket(params);
  }

  disconnect(reason: string): void {
    this.manualDisconnect = true;
    this.connectParams = undefined;
    this.clearReconnectTimer();
    this.disconnectSocket(reason);
  }

  suspend(reason: string): void {
    this.manualDisconnect = true;
    this.clearReconnectTimer();
    this.disconnectSocket(reason);
  }

  resume(): void {
    const params = this.connectParams;
    if (params === undefined || this.connected || this.socket !== undefined) {
      return;
    }
    this.manualDisconnect = false;
    this.openSocket(params);
  }

  emit(eventName: string, payload: KRAny): boolean {
    if (!this.connected) {
      return false;
    }
    this.sendRaw(`${SOCKET_IO_EVENT}${JSON.stringify([eventName, payload])}`);
    return true;
  }

  private openSocket(params: OhosSocketIoConnectParams): void {
    const socket = webSocket.createWebSocket();
    this.socket = socket;
    this.connected = false;

    socket.on('open', () => {
      console.info('[OhosSocketIo] websocket_open');
    });
    socket.on('message', (_error, data) => {
      if (this.socket !== socket) {
        return;
      }
      this.handleFrame(this.frameToString(data), params);
    });
    socket.on('close', (_error, closeResult) => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      const reason = closeResult?.reason ?? 'close';
      console.info(`[OhosSocketIo] websocket_close code=${closeResult?.code ?? 0} reason=${reason}`);
      this.listener.onDisconnected?.(reason);
      if (!this.manualDisconnect) {
        this.scheduleReconnect('close');
      }
    });
    socket.on('error', (error) => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      const message = error?.message ?? 'unknown';
      console.error(`[OhosSocketIo] websocket_error ${message}`);
      this.listener.onDisconnected?.(message);
      if (!this.manualDisconnect) {
        this.scheduleReconnect('error');
      }
    });

    const headers: Record<string, string> = params.headers ?? {};
    const options: WebSocketConnectOptions = {
      header: headers,
    };
    socket.connect(this.socketIoWebSocketUrl(params.socketUrl), options).catch((error: Error) => {
      if (this.socket !== socket) {
        return;
      }
      console.error(`[OhosSocketIo] websocket_connect_failed ${error.message}`);
      this.listener.onConnectError?.(error.message);
      if (!this.manualDisconnect) {
        this.scheduleReconnect('connect_failed');
      }
    });
  }

  private disconnectSocket(reason: string): void {
    const socket = this.socket;
    this.socket = undefined;
    this.connected = false;
    if (socket !== undefined) {
      const options: WebSocketCloseOptions = {
        code: 1000,
        reason: reason,
      };
      socket.close(options).catch(() => {});
    }
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== undefined) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
  }

  private scheduleReconnect(reason: string): void {
    const params = this.connectParams;
    if (params === undefined || this.reconnectTimer !== undefined) {
      return;
    }
    const delayMillis = Math.min(30000, 1000 * Math.pow(2, Math.min(this.reconnectAttempt, 5)));
    this.reconnectAttempt += 1;
    console.info(`[OhosSocketIo] reconnect_scheduled reason=${reason} delay=${delayMillis}`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.openSocket(params);
    }, delayMillis);
  }

  private handleFrame(frame: string, params: OhosSocketIoConnectParams): void {
    const decoded = decodeOhosSocketIoFrame(frame);
    if (decoded.kind === 'empty') {
      return;
    }
    if (decoded.kind === 'engine_open') {
      this.logEngineOpen(decoded);
      this.sendSocketIoConnect(params.auth);
      return;
    }
    if (decoded.kind === 'engine_close') {
      console.info('[OhosSocketIo] engine_close');
      this.disconnectSocket('engine_io_close');
      this.listener.onDisconnected?.('engine_io_close');
      if (!this.manualDisconnect) {
        this.scheduleReconnect('engine_io_close');
      }
      return;
    }
    if (decoded.kind === 'engine_ping') {
      console.info('[OhosSocketIo] engine_ping_received');
      this.sendRaw(ENGINE_IO_PONG);
      console.info('[OhosSocketIo] engine_pong_sent');
      return;
    }
    if (decoded.kind === 'socket_connect') {
      this.connected = true;
      this.reconnectAttempt = 0;
      this.listener.onConnected?.();
      return;
    }
    if (decoded.kind === 'socket_disconnect') {
      console.info('[OhosSocketIo] socket_disconnect');
      this.disconnectSocket('socket_disconnect');
      this.listener.onDisconnected?.('socket_disconnect');
      if (!this.manualDisconnect) {
        this.scheduleReconnect('socket_disconnect');
      }
      return;
    }
    if (decoded.kind === 'socket_connect_error') {
      const message = decoded.message ?? '';
      console.warn(`[OhosSocketIo] socket_connect_error ${message}`);
      this.listener.onConnectError?.(message);
      return;
    }
    if (decoded.kind === 'socket_event') {
      this.listener.onEvent?.(decoded.eventName ?? '', decoded.payload ?? {});
    } else if (decoded.kind === 'invalid_event') {
      console.warn(`[OhosSocketIo] event_decode_failed ${decoded.message ?? ''}`);
    }
  }

  private logEngineOpen(decoded: OhosSocketIoDecodedFrame): void {
    const pingInterval = decoded.pingInterval ?? 0;
    const pingTimeout = decoded.pingTimeout ?? 0;
    if (pingInterval > 0 || pingTimeout > 0) {
      console.info(`[OhosSocketIo] engine_open pingInterval=${pingInterval} pingTimeout=${pingTimeout}`);
    } else {
      console.info('[OhosSocketIo] engine_open');
    }
  }

  private sendSocketIoConnect(auth: KRRecord): void {
    console.info(`[OhosSocketIo] socket_auth_connect_sent authKeys=${Object.keys(auth).join(',')}`);
    this.sendRaw(`${SOCKET_IO_CONNECT}${JSON.stringify(auth)}`);
  }

  private sendRaw(text: string): void {
    this.socket?.send(text).catch((error: Error) => {
      console.warn(`[OhosSocketIo] send_failed ${error.message}`);
    });
  }

  private frameToString(data: string | ArrayBuffer): string {
    if (typeof data === 'string') {
      return data;
    }
    return this.decoder.decodeWithStream(new Uint8Array(data));
  }

  private socketIoWebSocketUrl(socketUrl: string): string {
    const withoutTrailingSlash = socketUrl.endsWith('/') ? socketUrl.substring(0, socketUrl.length - 1) : socketUrl;
    const wsScheme =
      withoutTrailingSlash.startsWith('https://')
        ? `wss://${withoutTrailingSlash.substring('https://'.length)}`
        : withoutTrailingSlash.startsWith('http://')
          ? `ws://${withoutTrailingSlash.substring('http://'.length)}`
          : withoutTrailingSlash;
    return `${wsScheme}${SOCKET_IO_PATH}?EIO=4&transport=websocket`;
  }
}
