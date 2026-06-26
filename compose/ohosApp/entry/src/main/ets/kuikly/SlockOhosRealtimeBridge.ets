import emitter from '@ohos.events.emitter';
import { KRAny, KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import Napi from 'libentry.so';
import { GeneratedRealtimeContract } from './GeneratedRealtimeContract';
import { OhosSocketIoClient } from './OhosSocketIoClient';
import { SLOCK_SOCKET_BASE_URL } from './SlockOhosApiConfig';
import { SlockOhosApiEnvironmentStore } from './SlockOhosApiEnvironmentStore';

const DEFAULT_SOCKET_URL = SLOCK_SOCKET_BASE_URL;
const HEARTBEAT_SILENCE_MILLIS = 90_000;
const INBOUND_SILENCE_MILLIS = 120_000;
const WATCHDOG_INTERVAL_MILLIS = 30_000;
const ENHANCED_NOTIFY_EVENT_NAME = 'slockEnhancedNotify';
const ENHANCED_NOTIFY_KEY_EVENT_NAME = 'eventName';
const ENHANCED_NOTIFY_KEY_DATA = 'data';
const ENHANCED_NOTIFY_KEY_SOURCE = 'source';

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
  private authRefreshInFlight: boolean = false;
  private enhancedNotifyCallback?: Callback<emitter.EventData>;

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
    this.bootstrapRealtime(controller);
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
        this.requestAuthRefresh('connect_error');
      },
      onEvent: (eventName, payload) => {
        this.handleSocketIoEvent(eventName, payload);
      }
    });
  }

  private bootstrapRealtime(controller?: KRNativeRenderController): void {
    this.controller = controller;
    this.installEnhancedNotifyBridge();
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
        }, params['forceReconnect'] === true);
        break;
      case 'disconnect':
        this.disconnectForRealtimeCommand((params['reason'] as string) ?? 'shared_disconnect');
        break;
      case 'syncResume':
        this.syncResume(Number(params['lastSeq'] ?? 0));
        break;
      case 'authRefreshFinished':
        this.authRefreshInFlight = false;
        console.info(`[SlockOhosRealtime] auth_refresh_finished reason=${(params['reason'] as string) ?? ''} success=${params['success'] === true}`);
        break;
      default:
        console.warn(`[SlockOhosRealtime] unsupported action=${action}`);
        break;
    }
  }

  onPageShow(): void {
    Napi.slockRealtimeOnAppShown();
    this.socketIo.resume();
    this.sendAppLifecycleEvent('show');
  }

  onPageHide(keepAlive: boolean = false): void {
    Napi.slockRealtimeOnAppHidden();
    if (keepAlive) {
      console.info('[SlockOhosRealtime] page_hide_keep_alive socket_retained');
      this.sendAppLifecycleEvent('hide_keep_alive');
      return;
    }
    this.socketIo.suspend('background');
  }

  disconnectForSessionEnd(reason: string): void {
    if (!Napi.slockRealtimeDisconnectForSessionEnd(reason)) {
      console.info(`[SlockOhosRealtime] disconnect_ignored reason=${reason}`);
      return;
    }
    this.disconnect(reason);
  }

  private connect(params: RealtimeConnectParams, forceReconnect: boolean = false): void {
    if (params.serverId.length === 0 || params.accessToken.length === 0) {
      console.warn('[SlockOhosRealtime] connect skipped: missing server/token');
      return;
    }
    const decision = Napi.slockRealtimeConnectDecision(
      params.serverId,
      params.accessToken,
      params.lastSeq,
      forceReconnect,
      this.connectionStateName()
    );
    if (decision.logMessage !== null && decision.logMessage.length > 0) {
      console.info(`[SlockOhosRealtime] ${decision.logMessage}`);
    }
    if (decision.sessionChanged) {
      this.joinedChannels.clear();
    }
    this.connectParams = {
      serverId: params.serverId,
      accessToken: params.accessToken,
      socketUrl: params.socketUrl,
      lastSeq: decision.resumeSeq,
    };
    if (!decision.shouldConnect) {
      return;
    }
    this.authRefreshInFlight = false;
    this.lastHeartbeatAtMillis = undefined;
    this.lastInboundEventAtMillis = undefined;
    this.disconnect('reconnect');
    this.socketIo.connect({
      socketUrl: SlockOhosApiEnvironmentStore.resolveSocketUrl(params.socketUrl.length > 0 ? params.socketUrl : DEFAULT_SOCKET_URL, this),
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

  private disconnectForRealtimeCommand(reason: string): void {
    if (Napi.slockRealtimeDisconnectForSessionEnd(reason)) {
      this.disconnect(reason);
      return;
    }
    console.info(`[SlockOhosRealtime] disconnect_local_only reason=${reason}`);
    this.disconnect(reason);
  }

  private disconnect(reason: string): void {
    if (reason !== 'reconnect') {
      this.joinedChannels.clear();
      this.connectParams = undefined;
    }
    this.authRefreshInFlight = false;
    this.stopHeartbeatWatchdog();
    this.socketIo.disconnect(reason);
  }

  private handleSocketIoEvent(eventName: string, payload: KRAny): void {
    this.markInbound(eventName);
    if (eventName === GeneratedRealtimeContract.SocketEvent.RoomsJoined) {
      this.onRoomsJoined();
      return;
    }
    if (eventName.length === 0) {
      return;
    }
    if (eventName === GeneratedRealtimeContract.SocketEvent.Heartbeat) {
      const heartbeat = payload as KRRecord;
      console.info(`[SlockOhosRealtime] app_heartbeat_received seq=${Number(heartbeat['seq'] ?? 0)}`);
    }
    const rawPayload = JSON.stringify(payload ?? {});
    const decision = Napi.slockRealtimeRawDecision(eventName, rawPayload);
    if (decision.logMessage.length > 0) {
      console.info(`[SlockOhosRealtime] ${decision.logMessage}`);
    }
    if (!decision.shouldFanOut || decision.serverId === null) {
      return;
    }
    this.sendRealtimeNotify({
      [GeneratedRealtimeContract.PayloadKey.ServerId]: decision.serverId,
      [GeneratedRealtimeContract.PayloadKey.EventName]: decision.eventName,
      [GeneratedRealtimeContract.PayloadKey.RawPayload]: decision.rawPayload,
    });
  }

  private onRoomsJoined(): void {
    const lastSeq = this.connectParams?.lastSeq ?? 0;
    console.info(`[SlockOhosRealtime] rooms_joined joinedRooms=${this.joinedChannels.size} lastSeq=${lastSeq}`);
    if (lastSeq > 0) {
      this.syncResume(lastSeq);
    }
  }

  private handleRoomCommand(action: string, channelId: string, source: string): void {
    const decision = Napi.slockRealtimeRoomCommandDecision(action, channelId, source);
    if (decision.logMessage.length > 0) {
      console.info(`[SlockOhosRealtime] ${decision.logMessage}`);
    }
    if (!decision.shouldJoin || decision.channelId === null || decision.channelId.length === 0) {
      return;
    }
    this.joinedChannels.add(decision.channelId);
    this.emitJoinChannel(decision.channelId);
  }

  private emitJoinChannel(channelId: string): void {
    if (!this.socketIo.emit(GeneratedRealtimeContract.SocketEvent.JoinChannel, channelId)) {
      console.info('[SlockOhosRealtime] join_channel_deferred');
    }
  }

  private rejoinChannels(reason: string): void {
    this.joinedChannels.forEach((channelId) => {
      console.info(`[SlockOhosRealtime] rejoin_channel reason=${reason}`);
      this.socketIo.emit(GeneratedRealtimeContract.SocketEvent.JoinChannel, channelId);
    });
  }

  private installEnhancedNotifyBridge(): void {
    if (this.enhancedNotifyCallback !== undefined) {
      return;
    }
    this.enhancedNotifyCallback = (event: emitter.EventData) => {
      const data = event.data as Record<string, string>;
      const stringify = data.stringify ?? '{}';
      try {
        this.handleEnhancedNotify(JSON.parse(stringify) as KRRecord);
      } catch (error) {
        console.warn(`[SlockOhosRealtime] enhanced_notify_parse_failed message=${JSON.stringify(error)}`);
      }
    };
    emitter.on(ENHANCED_NOTIFY_EVENT_NAME, this.enhancedNotifyCallback);
    console.info('[SlockOhosRealtime] enhanced_notify_bridge_installed');
  }

  private handleEnhancedNotify(envelope: KRRecord): void {
    const eventName = (envelope[ENHANCED_NOTIFY_KEY_EVENT_NAME] as string) ?? '';
    const source = (envelope[ENHANCED_NOTIFY_KEY_SOURCE] as string) ?? 'notify';
    const params = (envelope[ENHANCED_NOTIFY_KEY_DATA] as KRRecord) ?? {};
    if (eventName === GeneratedRealtimeContract.Notify.RoomsEventName) {
      this.handleRoomCommand(
        (params[GeneratedRealtimeContract.PayloadKey.Action] as string) ?? '',
        (params[GeneratedRealtimeContract.PayloadKey.ChannelId] as string) ?? '',
        source
      );
    }
  }

  private syncResume(lastSeq: number): void {
    if (lastSeq <= 0) {
      return;
    }
    console.info(`[SlockOhosRealtime] sync_resume_emit lastSeq=${lastSeq}`);
    this.socketIo.emit(GeneratedRealtimeContract.SocketEvent.SyncResume, { lastSeq: lastSeq });
  }

  private markInbound(eventName?: string): void {
    const now = Date.now();
    this.lastInboundEventAtMillis = now;
    if (eventName === GeneratedRealtimeContract.SocketEvent.Heartbeat) {
      this.lastHeartbeatAtMillis = now;
    }
  }

  private connectionStateName(): string {
    return this.socketIo.isConnected() ? 'Connected' : 'Disconnected';
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
    if (this.requestAuthRefresh('heartbeat_silence')) {
      return;
    }
    this.socketIo.connect({
      socketUrl: SlockOhosApiEnvironmentStore.resolveSocketUrl(params.socketUrl.length > 0 ? params.socketUrl : DEFAULT_SOCKET_URL, this),
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

  private requestAuthRefresh(reason: string): boolean {
    const params = this.connectParams;
    if (params === undefined || params.serverId.length === 0) {
      return false;
    }
    if (this.authRefreshInFlight) {
      console.info(`[SlockOhosRealtime] auth_refresh_skip reason=${reason} result=in_flight`);
      return true;
    }
    this.authRefreshInFlight = true;
    console.info(`[SlockOhosRealtime] auth_refresh_request reason=${reason} serverId=${params.serverId}`);
    this.sendRealtimeNotify({
      action: 'authRefreshRequired',
      reason: reason,
      [GeneratedRealtimeContract.PayloadKey.ServerId]: params.serverId,
    });
    return true;
  }

  private sendAppLifecycleEvent(event: string): void {
    this.sendRealtimeNotify({
      action: 'appLifecycle',
      event: event,
      pageName: 'compose_slock_app_shell',
    });
  }

  private sendRealtimeNotify(data: KRRecord): void {
    emitter.emit(GeneratedRealtimeContract.Notify.EventName, {
      data: {
        eventName: GeneratedRealtimeContract.Notify.EventName,
        stringify: JSON.stringify(data),
      },
    });
  }
}

const slockOhosRealtimeBridge = new SlockOhosRealtimeBridge();

export default slockOhosRealtimeBridge;
