import { invoke } from '@kuiklybase/knoi';
import { KRAny } from '@kuikly-open/render';
import { OhosSocketIoConnectParams, OhosSocketIoListener } from './OhosSocketIoClient';

export const SLOCK_OHOS_CURL_SOCKET_IO_OBSERVER_SERVICE = 'slockOhosCurlSocketIoObserver';

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

/** Whether this server's next connection should use curl (Settings switch, else the server flag). */
export function slockOhosCurlSocketIoEnabledFor(serverId: string): boolean {
  try {
    return invoke<boolean>('slockOhosCurlSocketIoEnabledFor', serverId);
  } catch (error) {
    console.warn(`[OhosCurlSocketIo] enabled_check_failed ${String(error)}`);
    return false;
  }
}

/**
 * Task #154: same contract as OhosSocketIoClient, over the native curl client. The native session
 * reconnects by itself with backoff, so there is no reconnect timer here; suspend/resume close and
 * reopen the session. Callbacks of a closed session are dropped by handle.
 */
export class OhosCurlSocketIoClient implements OhosRealtimeSocket {
  private handle?: string;
  private connectParams?: OhosSocketIoConnectParams;
  private listener: OhosSocketIoListener = {};
  private connected: boolean = false;

  setListener(listener: OhosSocketIoListener): void {
    this.listener = listener;
  }

  isConnected(): boolean {
    return this.connected;
  }

  connect(params: OhosSocketIoConnectParams): void {
    this.connectParams = params;
    this.closeSession('reconnect');
    this.openSession(params);
  }

  disconnect(reason: string): void {
    this.connectParams = undefined;
    this.closeSession(reason);
  }

  suspend(reason: string): void {
    this.closeSession(reason);
  }

  resume(): void {
    const params = this.connectParams;
    if (params === undefined || this.handle !== undefined) {
      return;
    }
    this.openSession(params);
  }

  emit(eventName: string, payload: KRAny): boolean {
    const handle = this.handle;
    if (!this.connected || handle === undefined) {
      return false;
    }
    try {
      return invoke<boolean>('slockOhosCurlSocketIoEmit', handle, eventName, JSON.stringify(payload));
    } catch (error) {
      console.warn(`[OhosCurlSocketIo] emit_failed eventName=${eventName} ${String(error)}`);
      return false;
    }
  }

  onNativeState(handle: string, state: string, code: number, detail: string): void {
    if (handle !== this.handle) {
      return;
    }
    if (state === 'Connected') {
      this.connected = true;
      this.listener.onConnected?.();
    } else if (state === 'Disconnected') {
      // "closed" is the final state after our own close; only a dropped connection is reported.
      if (detail !== 'transport_closed') return;
      this.connected = false;
      this.listener.onDisconnected?.(`transport_closed curlCode=${code}`);
    } else if (state === 'Error') {
      if (detail === 'binary_frame_unsupported') {
        console.warn('[OhosCurlSocketIo] binary_frame_unsupported');
        return;
      }
      this.connected = false;
      // "websocket_connect" (could not open the WebSocket) or a server CONNECT_ERROR payload.
      this.listener.onConnectError?.(detail === 'websocket_connect' ? `websocket_connect curlCode=${code}` : detail);
    } else {
      console.info(`[OhosCurlSocketIo] state=${state} detail=${detail}`);
    }
  }

  onNativeEvent(handle: string, eventName: string, payloadJson: string): void {
    if (handle !== this.handle) {
      return;
    }
    let payload: KRAny = {};
    try {
      // An event without arguments arrives as "null"; the webSocket client hands over {} then.
      const parsed = JSON.parse(payloadJson) as KRAny;
      payload = parsed === null ? {} : parsed;
    } catch (error) {
      console.warn(`[OhosCurlSocketIo] event_decode_failed eventName=${eventName}`);
      return;
    }
    this.listener.onEvent?.(eventName, payload);
  }

  private openSession(params: OhosSocketIoConnectParams): void {
    this.connected = false;
    let handle = '';
    try {
      handle = invoke<string>(
        'slockOhosCurlSocketIoOpen',
        params.socketUrl,
        JSON.stringify(params.auth),
        JSON.stringify(params.headers ?? {})
      );
    } catch (error) {
      console.error(`[OhosCurlSocketIo] open_failed ${String(error)}`);
    }
    if (handle.length === 0) {
      this.listener.onConnectError?.('curl_socket_io_unavailable');
      return;
    }
    this.handle = handle;
    slockOhosCurlSocketIoObserver.register(handle, this);
    console.info(`[OhosCurlSocketIo] session_open handle=${handle}`);
  }

  private closeSession(reason: string): void {
    const handle = this.handle;
    this.handle = undefined;
    this.connected = false;
    if (handle === undefined) {
      return;
    }
    slockOhosCurlSocketIoObserver.unregister(handle);
    console.info(`[OhosCurlSocketIo] session_close handle=${handle} reason=${reason}`);
    try {
      invoke<void>('slockOhosCurlSocketIoClose', handle);
    } catch (error) {
      console.warn(`[OhosCurlSocketIo] close_failed ${String(error)}`);
    }
  }
}

/** KNOI service the Kotlin bridge calls back on; routes by session handle. */
export class SlockOhosCurlSocketIoObserver {
  private readonly clients: Map<string, OhosCurlSocketIoClient> = new Map();

  register(handle: string, client: OhosCurlSocketIoClient): void {
    this.clients.set(handle, client);
  }

  unregister(handle: string): void {
    this.clients.delete(handle);
  }

  onState(payload: Record<string, Object>): void {
    const handle = payload['handle'] as string | undefined;
    if (handle === undefined) return;
    this.clients.get(handle)?.onNativeState(
      handle,
      (payload['state'] as string | undefined) ?? '',
      (payload['code'] as number | undefined) ?? 0,
      (payload['detail'] as string | undefined) ?? ''
    );
  }

  onEvent(payload: Record<string, Object>): void {
    const handle = payload['handle'] as string | undefined;
    if (handle === undefined) return;
    this.clients.get(handle)?.onNativeEvent(
      handle,
      (payload['eventName'] as string | undefined) ?? '',
      (payload['payloadJson'] as string | undefined) ?? 'null'
    );
  }
}

export const slockOhosCurlSocketIoObserver = new SlockOhosCurlSocketIoObserver();
