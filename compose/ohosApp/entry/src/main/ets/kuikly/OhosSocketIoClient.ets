import { client_socket } from '@ohos/socketio';
import { KRAny, KRRecord } from '@kuikly-open/render';

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
}

export class OhosSocketIoClient {
  private socket?: client_socket;
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
    this.disconnectSocket('reconnect');
    this.openSocket(params);
  }

  disconnect(reason: string): void {
    this.connectParams = undefined;
    this.disconnectSocket(reason);
  }

  suspend(reason: string): void {
    this.disconnectSocket(reason);
  }

  resume(): void {
    const params = this.connectParams;
    if (params === undefined || this.connected || this.socket !== undefined) {
      return;
    }
    this.openSocket(params);
  }

  emit(eventName: string, payload: KRAny): boolean {
    const socket = this.socket;
    if (!this.connected || socket === undefined || !socket.opened()) {
      return false;
    }
    try {
      socket.emit(eventName, payload as ESObject);
      return true;
    } catch (error) {
      console.warn(`[OhosSocketIo] emit_failed event=${eventName} error=${error}`);
      return false;
    }
  }

  private openSocket(params: OhosSocketIoConnectParams): void {
    const socket = new client_socket();
    this.socket = socket;
    this.connected = false;

    socket.set_logs_default();
    socket.set_reconnect_attempts(10);
    socket.set_reconnect_delay(1000);
    socket.set_reconnect_delay_max(30000);
    this.applyHeaders(socket, params.headers);
    this.applyAuthOptions(socket, params.auth);
    this.registerListeners(socket);

    try {
      socket.connect(this.normalizeSocketUrl(params.socketUrl));
      console.info('[OhosSocketIo] client_socket_connect_requested');
    } catch (error) {
      console.error(`[OhosSocketIo] client_socket_connect_failed ${error}`);
      this.listener.onConnectError?.(`${error}`);
    }
  }

  private registerListeners(socket: client_socket): void {
    socket.set_open_listener(() => {
      if (this.socket !== socket) {
        return;
      }
      console.info('[OhosSocketIo] client_open');
      this.connected = true;
      this.listener.onConnected?.();
    });
    socket.set_socket_open_listener((nsp: string) => {
      if (this.socket !== socket) {
        return;
      }
      console.info(`[OhosSocketIo] socket_open nsp=${nsp}`);
      this.connected = true;
      this.listener.onConnected?.();
    });
    socket.set_fail_listener(() => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      console.warn('[OhosSocketIo] client_fail');
      this.listener.onConnectError?.('client_fail');
    });
    socket.set_reconnecting_listener(() => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      console.info('[OhosSocketIo] reconnecting');
    });
    socket.set_reconnect_listener(() => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = true;
      console.info('[OhosSocketIo] reconnect');
      this.listener.onConnected?.();
    });
    socket.set_close_listener((reason: string) => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      const closeReason = reason.length > 0 ? reason : 'close';
      console.info(`[OhosSocketIo] close reason=${closeReason}`);
      this.listener.onDisconnected?.(closeReason);
    });
    socket.set_socket_close_listener((nsp: string) => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      console.info(`[OhosSocketIo] socket_close nsp=${nsp}`);
      this.listener.onDisconnected?.('socket_close');
    });
    socket.on_error((message: string) => {
      if (this.socket !== socket) {
        return;
      }
      console.warn(`[OhosSocketIo] socket_error ${message}`);
      this.listener.onConnectError?.(message);
    });
    socket.on('disconnect', (data: string) => {
      if (this.socket !== socket) {
        return;
      }
      this.connected = false;
      const reason = data.length > 0 ? data : 'disconnect';
      console.info(`[OhosSocketIo] disconnect event=${reason}`);
      this.listener.onDisconnected?.(reason);
    });
    socket.on('ping_pong', (data: string) => {
      if (this.socket !== socket) {
        return;
      }
      console.info(`[OhosSocketIo] ping_pong ${data}`);
    });
    this.registerWireEventListeners(socket);
  }

  private registerWireEventListeners(socket: client_socket): void {
    const eventNames: Array<string> = [
      'rooms:joined',
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
    eventNames.forEach((eventName) => {
      socket.on(eventName, (eventJson: string) => {
        if (this.socket !== socket) {
          return;
        }
        this.connected = true;
        this.listener.onEvent?.(eventName, this.decodePayload(eventName, eventJson));
      });
    });
  }

  private decodePayload(eventName: string, eventJson: string): KRAny {
    if (eventJson.length === 0) {
      return {};
    }
    try {
      return JSON.parse(eventJson) as KRAny;
    } catch (error) {
      console.warn(`[OhosSocketIo] event_decode_failed event=${eventName} error=${error}`);
      return eventJson;
    }
  }

  private applyHeaders(socket: client_socket, headers?: Record<string, string>): void {
    if (headers === undefined) {
      return;
    }
    const headerLines = Object.entries(headers)
      .filter((entry) => entry[0].length > 0 && entry[1].length > 0)
      .map((entry) => `${entry[0]}: ${entry[1]}`);
    if (headerLines.length === 0) {
      return;
    }
    socket.set_headers(headerLines.join('\r\n'));
  }

  private applyAuthOptions(socket: client_socket, auth: KRRecord): void {
    const token = auth['token'] as string;
    const serverId = auth['serverId'] as string;
    const queryParts: Array<string> = [];
    if (token !== undefined && token.length > 0) {
      queryParts.push(`token=${encodeURIComponent(token)}`);
    }
    if (serverId !== undefined && serverId.length > 0) {
      queryParts.push(`serverId=${encodeURIComponent(serverId)}`);
    }
    if (queryParts.length > 0) {
      socket.set_option(queryParts.join('&'));
    }
  }

  private disconnectSocket(reason: string): void {
    const socket = this.socket;
    this.socket = undefined;
    this.connected = false;
    if (socket === undefined) {
      return;
    }
    try {
      socket.clear_con_listeners();
      socket.clear_socket_listeners();
      socket.off_error();
      socket.close();
      console.info(`[OhosSocketIo] client_close_requested reason=${reason}`);
    } catch (error) {
      console.warn(`[OhosSocketIo] client_close_failed reason=${reason} error=${error}`);
    }
  }

  private normalizeSocketUrl(socketUrl: string): string {
    return socketUrl.endsWith('/') ? socketUrl.substring(0, socketUrl.length - 1) : socketUrl;
  }
}
