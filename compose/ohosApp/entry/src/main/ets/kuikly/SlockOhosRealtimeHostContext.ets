import emitter from '@ohos.events.emitter';
import { KRAny, KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import { registerSingleServiceProvider } from '@kuiklybase/knoi';
import { GeneratedRealtimeContract } from './GeneratedRealtimeContract';
import { OhosSocketIoClient, OhosSocketIoConnectParams } from './OhosSocketIoClient';
import { OhosCurlSocketIoClient, OhosRealtimeSocket, slockOhosCurlSocketIoEnabledFor } from './OhosCurlSocketIoClient';
import { SLOCK_SOCKET_BASE_URL } from './SlockOhosApiConfig';
import { SlockOhosApiEnvironmentStore } from './SlockOhosApiEnvironmentStore';
import slockOhosRealtimeKnoiBridge from './SlockOhosRealtimeKnoiBridge';

const DEFAULT_SOCKET_URL = SLOCK_SOCKET_BASE_URL;
const HEARTBEAT_SILENCE_MILLIS = 90_000;
const INBOUND_SILENCE_MILLIS = 120_000;
const WATCHDOG_INTERVAL_MILLIS = 30_000;
const ENHANCED_NOTIFY_EVENT_NAME = 'slockEnhancedNotify';
const ENHANCED_NOTIFY_KEY_EVENT_NAME = 'eventName';
const ENHANCED_NOTIFY_KEY_DATA = 'data';
const ENHANCED_NOTIFY_KEY_SOURCE = 'source';
const ENHANCED_NOTIFY_SINK_SERVICE = 'slockOhosEnhancedNotifySink';

interface RealtimeConnectParams {
  serverId: string;
  accessToken: string;
  socketUrl: string;
  lastSeq: number;
  authorityScopeLeaseId: string;
  authorityPrincipalId: string;
  authorityAuthGeneration: number;
  authorityConnectionFamilyId: string;
}

interface RealtimeProcessIdentity {
  environmentId: string;
  socketUrl: string;
  principalId: string;
  authGeneration: number;
  connectionFamilyId: string;
  accessToken: string;
}

export interface SlockOhosRealtimeSharedIngress {
  publishEvent(eventDataJson: string): boolean;
}

class SlockOhosRealtimeRuntime {
  // Task #154: picked per connect (Settings switch, else mobile_curl_socketio_v0); see selectSocket().
  private socketIo: OhosRealtimeSocket = new OhosSocketIoClient();
  private socketIoIsCurl: boolean = false;
  private readonly joinedChannels: Set<string> = new Set();
  private connectParams?: RealtimeConnectParams;
  private watchdogTimer?: number;
  private lastHeartbeatAtMillis?: number;
  private lastInboundEventAtMillis?: number;
  private authRefreshInFlight: boolean = false;
  private connectInFlight: boolean = false;
  private generation: number = 0;
  private readonly serverId: string;
  private readonly runtimeId: string;
  private readonly owner: Object;
  private readonly notify: (data: KRRecord) => void;

  constructor(serverId: string, owner: Object, notify: (data: KRRecord) => void) {
    this.serverId = serverId;
    this.runtimeId = `server:${serverId}`;
    this.owner = owner;
    this.notify = notify;
  }

  currentLastSeq(): number {
    return slockOhosRealtimeKnoiBridge.currentResumeSeq(this.runtimeId);
  }

  connect(params: RealtimeConnectParams, forceReconnect: boolean): void {
    if (params.serverId.length === 0 || params.accessToken.length === 0) {
      console.warn(`[SlockOhosRealtime] connect_skip reason=missing_server_or_token serverId=${this.serverId}`);
      return;
    }
    const decision = slockOhosRealtimeKnoiBridge.connectDecision(
      this.runtimeId,
      params.serverId,
      params.accessToken,
      params.lastSeq,
      forceReconnect,
      this.connectionStateName()
    );
    if (decision.logMessage.length > 0) {
      console.info(`[SlockOhosRealtime] serverId=${this.serverId} ${decision.logMessage}`);
    }
    if (decision.sessionChanged) {
      this.joinedChannels.clear();
    }
    this.connectParams = {
      serverId: params.serverId,
      accessToken: params.accessToken,
      socketUrl: params.socketUrl,
      lastSeq: decision.resumeSeq,
      authorityScopeLeaseId: params.authorityScopeLeaseId,
      authorityPrincipalId: params.authorityPrincipalId,
      authorityAuthGeneration: params.authorityAuthGeneration,
      authorityConnectionFamilyId: params.authorityConnectionFamilyId,
    };
    if (!decision.shouldConnect) {
      return;
    }
    this.authRefreshInFlight = false;
    this.lastHeartbeatAtMillis = undefined;
    this.lastInboundEventAtMillis = undefined;
    this.generation += 1;
    const capturedGeneration = this.generation;
    this.selectSocket(params.serverId);
    this.installSocketListener(capturedGeneration);
    this.connectInFlight = true;
    this.socketIo.connect(this.socketConnectParams(this.connectParams));
  }

  onAppShown(): void {
    slockOhosRealtimeKnoiBridge.onAppShown(this.runtimeId);
    this.socketIo.resume();
  }

  onAppHidden(keepAlive: boolean): void {
    slockOhosRealtimeKnoiBridge.onAppHidden(this.runtimeId);
    if (!keepAlive) {
      this.socketIo.suspend('background');
    }
  }

  disconnectForSessionEnd(reason: string): void {
    if (!slockOhosRealtimeKnoiBridge.disconnectForSessionEnd(this.runtimeId, reason)) {
      console.info(`[SlockOhosRealtime] disconnect_ignored reason=${reason} serverId=${this.serverId}`);
      return;
    }
    this.disconnect(reason);
  }

  disconnect(reason: string): void {
    this.generation += 1;
    this.joinedChannels.clear();
    this.connectParams = undefined;
    this.authRefreshInFlight = false;
    this.connectInFlight = false;
    this.stopHeartbeatWatchdog();
    this.socketIo.disconnect(reason);
    slockOhosRealtimeKnoiBridge.releaseRuntime(this.runtimeId);
  }

  authRefreshFinished(reason: string, success: boolean): void {
    this.authRefreshInFlight = false;
    console.info(`[SlockOhosRealtime] auth_refresh_finished serverId=${this.serverId} reason=${reason} success=${success}`);
  }

  advanceResumeCursor(
    appliedSeq: number,
    authorityScopeLeaseId: string,
    authorityPrincipalId: string,
    authorityAuthGeneration: number,
    authorityConnectionFamilyId: string
  ): boolean {
    const params = this.connectParams;
    if (params === undefined) {
      console.info(`[SlockOhosRealtime] cursor_advance_skip reason=missing_runtime_identity serverId=${this.serverId} appliedSeq=${appliedSeq}`);
      return false;
    }
    const decision = slockOhosRealtimeKnoiBridge.advanceAppliedSeq(
      this.runtimeId,
      params.serverId,
      params.authorityScopeLeaseId,
      params.authorityPrincipalId,
      params.authorityAuthGeneration,
      params.authorityConnectionFamilyId,
      this.serverId,
      authorityScopeLeaseId,
      authorityPrincipalId,
      authorityAuthGeneration,
      authorityConnectionFamilyId,
      appliedSeq
    );
    if (!decision.shouldAdvance) {
      console.info(`[SlockOhosRealtime] cursor_advance_skip reason=${decision.reason} serverId=${this.serverId} appliedSeq=${appliedSeq}`);
      return false;
    }
    params.lastSeq = decision.resumeSeq;
    console.info(`[SlockOhosRealtime] cursor_advance result=applied serverId=${this.serverId} requestedSeq=${appliedSeq} resumeSeq=${decision.resumeSeq}`);
    return true;
  }

  handleRoomCommand(action: string, channelId: string, source: string): void {
    const decision = slockOhosRealtimeKnoiBridge.roomCommandDecision(
      this.runtimeId,
      action,
      channelId,
      source
    );
    if (decision.logMessage.length > 0) {
      console.info(`[SlockOhosRealtime] serverId=${this.serverId} ${decision.logMessage}`);
    }
    if (decision.channelId.length > 0) {
      if (decision.joinedChannelAction === 'join' || decision.joinedChannelAction === 'rejoin') {
        this.joinedChannels.add(decision.channelId);
      } else if (decision.joinedChannelAction === 'leave') {
        this.joinedChannels.delete(decision.channelId);
      }
    }
    if (decision.shouldEmit) {
      this.emitRoomCommand(decision.eventName, decision.payload);
    }
  }

  private selectSocket(serverId: string): void {
    const useCurl = slockOhosCurlSocketIoEnabledFor(serverId);
    if (useCurl === this.socketIoIsCurl) {
      return;
    }
    // Leave the old stack before the other one connects; its late callbacks fail isCurrent().
    this.socketIo.disconnect('transport_switch');
    this.socketIo = useCurl ? new OhosCurlSocketIoClient() : new OhosSocketIoClient();
    this.socketIoIsCurl = useCurl;
    console.info(`[SlockOhosRealtime] transport_select stack=${useCurl ? 'curl' : 'ohos_websocket'} serverId=${serverId}`);
  }

  private installSocketListener(capturedGeneration: number): void {
    this.socketIo.setListener({
      onConnected: () => {
        if (!this.isCurrent(capturedGeneration)) return;
        this.markInbound();
        this.connectInFlight = false;
        this.startHeartbeatWatchdog(capturedGeneration);
        console.info(`[SlockOhosRealtime] socket_io_connected serverId=${this.serverId} generation=${capturedGeneration}`);
        this.rejoinChannels('connect');
      },
      onDisconnected: (reason: string) => {
        if (!this.isCurrent(capturedGeneration)) return;
        this.stopHeartbeatWatchdog();
        this.connectInFlight = false;
        console.info(`[SlockOhosRealtime] socket_io_disconnected serverId=${this.serverId} reason=${reason}`);
      },
      onConnectError: (message: string) => {
        if (!this.isCurrent(capturedGeneration)) return;
        this.markInbound();
        this.connectInFlight = false;
        console.warn(`[SlockOhosRealtime] socket_io_connect_error serverId=${this.serverId} ${message}`);
        this.requestAuthRefresh('connect_error');
      },
      onEvent: (eventName, payload) => {
        if (!this.isCurrent(capturedGeneration)) return;
        this.handleSocketIoEvent(eventName, payload);
      }
    });
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
      console.info(`[SlockOhosRealtime] app_heartbeat_received serverId=${this.serverId} seq=${Number(heartbeat['seq'] ?? 0)}`);
    }
    const rawPayload = JSON.stringify(payload ?? {});
    const decision = slockOhosRealtimeKnoiBridge.rawDecision(
      this.runtimeId,
      this.serverId,
      eventName,
      rawPayload
    );
    if (decision.logMessage.length > 0) {
      console.info(`[SlockOhosRealtime] ${decision.logMessage}`);
    }
    if (!decision.shouldFanOut) {
      return;
    }
    const params = this.connectParams;
    if (params === undefined) {
      return;
    }
    this.notify({
      serverId: params.serverId,
      authorityScopeLeaseId: params.authorityScopeLeaseId,
      authorityPrincipalId: params.authorityPrincipalId,
      authorityAuthGeneration: params.authorityAuthGeneration,
      authorityConnectionFamilyId: params.authorityConnectionFamilyId,
      eventName: decision.eventName,
      rawPayload: decision.rawPayload,
    });
  }

  private onRoomsJoined(): void {
    const lastSeq = this.currentLastSeq();
    console.info(`[SlockOhosRealtime] rooms_joined serverId=${this.serverId} joinedRooms=${this.joinedChannels.size} lastSeq=${lastSeq}`);
    if (lastSeq > 0) {
      this.emitSocketEvent(GeneratedRealtimeContract.SocketEvent.SyncResume, { 'lastSeq': lastSeq });
    }
  }

  private emitRoomCommand(eventName: string, payload: string): void {
    if (!this.emitSocketEvent(eventName, { 'channelId': payload })) {
      console.info(`[SlockOhosRealtime] room_command_deferred serverId=${this.serverId} eventName=${eventName}`);
    }
  }

  private rejoinChannels(reason: string): void {
    this.joinedChannels.forEach((channelId) => {
      console.info(`[SlockOhosRealtime] rejoin_channel serverId=${this.serverId} reason=${reason}`);
      this.emitRoomCommand(GeneratedRealtimeContract.SocketEvent.JoinChannel, channelId);
    });
  }

  private emitSocketEvent(eventName: string, payload: KRRecord): boolean {
    if (eventName === GeneratedRealtimeContract.SocketEvent.JoinChannel || eventName === GeneratedRealtimeContract.SocketEvent.LeaveChannel) {
      return this.socketIo.emit(eventName, (payload['channelId'] as string) ?? '');
    }
    return this.socketIo.emit(eventName, payload);
  }

  private markInbound(eventName?: string): void {
    const now = Date.now();
    this.lastInboundEventAtMillis = now;
    if (eventName === GeneratedRealtimeContract.SocketEvent.Heartbeat) {
      this.lastHeartbeatAtMillis = now;
    }
  }

  private startHeartbeatWatchdog(capturedGeneration: number): void {
    this.stopHeartbeatWatchdog();
    this.watchdogTimer = setInterval(() => {
      if (this.isCurrent(capturedGeneration)) {
        this.checkHeartbeatSilence();
      }
    }, WATCHDOG_INTERVAL_MILLIS);
  }

  private stopHeartbeatWatchdog(): void {
    if (this.watchdogTimer !== undefined) {
      clearInterval(this.watchdogTimer);
      this.watchdogTimer = undefined;
    }
  }

  private checkHeartbeatSilence(): void {
    if (!this.socketIo.isConnected()) return;
    const heartbeatAt = this.lastHeartbeatAtMillis;
    const inboundAt = this.lastInboundEventAtMillis;
    if (heartbeatAt === undefined || inboundAt === undefined) return;
    const now = Date.now();
    if (now - heartbeatAt <= HEARTBEAT_SILENCE_MILLIS || now - inboundAt <= INBOUND_SILENCE_MILLIS) return;
    const params = this.connectParams;
    if (params === undefined) return;
    console.warn(`[SlockOhosRealtime] heartbeat_silence_reconnect serverId=${this.serverId}`);
    this.lastHeartbeatAtMillis = undefined;
    this.lastInboundEventAtMillis = now;
    if (!this.requestAuthRefresh('heartbeat_silence')) {
      this.connect(params, true);
    }
  }

  private requestAuthRefresh(reason: string): boolean {
    const params = this.connectParams;
    if (params === undefined || params.serverId.length === 0) return false;
    if (this.authRefreshInFlight) {
      console.info(`[SlockOhosRealtime] auth_refresh_skip serverId=${this.serverId} reason=${reason} result=in_flight`);
      return true;
    }
    this.authRefreshInFlight = true;
    this.notify({
      action: 'authRefreshRequired',
      reason: reason,
      serverId: params.serverId,
    });
    return true;
  }

  private connectionStateName(): string {
    if (this.socketIo.isConnected()) return 'Connected';
    return this.connectInFlight ? 'Connecting' : 'Disconnected';
  }

  private socketConnectParams(params: RealtimeConnectParams): OhosSocketIoConnectParams {
    return {
      socketUrl: SlockOhosApiEnvironmentStore.resolveSocketUrl(params.socketUrl.length > 0 ? params.socketUrl : DEFAULT_SOCKET_URL, this.owner),
      auth: {
        token: params.accessToken,
        serverId: params.serverId,
        clientKind: 'mobile',
      },
      headers: {
        'Authorization': `Bearer ${params.accessToken}`,
        'X-Server-Id': params.serverId,
      }
    };
  }

  private isCurrent(capturedGeneration: number): boolean {
    return this.generation === capturedGeneration && this.connectParams !== undefined;
  }
}

class SlockOhosEnhancedNotifySink {
  private readonly hostContext: SlockOhosRealtimeHostContext;

  constructor(hostContext: SlockOhosRealtimeHostContext) {
    this.hostContext = hostContext;
  }

  onEvent(eventName: string, eventDataJson: string, envelopeJson: string): void {
    this.hostContext.handleSharedEnhancedNotifyEvent(eventName, eventDataJson, envelopeJson);
  }
}

export class SlockOhosRealtimeHostContext {
  private controller?: KRNativeRenderController;
  private readonly runtimes: Map<string, SlockOhosRealtimeRuntime> = new Map();
  private processIdentity?: RealtimeProcessIdentity;
  private foregroundEpoch: number = 0;
  private appVisible: boolean = false;
  private enhancedNotifyCallback?: Callback<emitter.EventData>;
  private enhancedNotifySinkInstalled: boolean = false;
  private readonly sharedIngress: SlockOhosRealtimeSharedIngress;

  constructor(sharedIngress: SlockOhosRealtimeSharedIngress = slockOhosRealtimeKnoiBridge) {
    this.sharedIngress = sharedIngress;
  }

  bindController(controller?: KRNativeRenderController): void {
    this.controller = controller;
  }

  bootstrapRealtime(): void {
    this.installEnhancedNotifySink();
    this.installEnhancedNotifyBridge();
  }

  handleCommand(params: KRRecord): void {
    const action = params['action'] as string;
    switch (action) {
      case 'reconcile':
        this.reconcile(params);
        break;
      case 'connect':
        this.reconcileLegacyConnect(params);
        break;
      case 'disconnect':
        this.disconnectForRealtimeCommand(
          (params['reason'] as string) ?? 'shared_disconnect',
          (params['serverId'] as string) ?? ''
        );
        break;
      case 'authRefreshFinished':
        this.runtimes.forEach((runtime) => runtime.authRefreshFinished(
          (params['reason'] as string) ?? '',
          params['success'] === true
        ));
        break;
      case 'cursorAdvance': {
        const serverId = ((params['serverId'] as string) ?? '').trim();
        const runtime = this.runtimes.get(serverId);
        if (runtime === undefined) {
          console.info(`[SlockOhosRealtime] cursor_advance_skip reason=missing_runtime serverId=${serverId}`);
          break;
        }
        runtime.advanceResumeCursor(
          Number(params['appliedSeq'] ?? 0),
          ((params['authorityScopeLeaseId'] as string) ?? '').trim(),
          ((params['authorityPrincipalId'] as string) ?? '').trim(),
          Number(params['authorityAuthGeneration'] ?? 0),
          ((params['authorityConnectionFamilyId'] as string) ?? '').trim()
        );
        break;
      }
      default:
        console.warn(`[SlockOhosRealtime] unsupported action=${action}`);
        break;
    }
  }

  onPageShow(): void {
    this.foregroundEpoch = nextSlockOhosForegroundEpoch(this.foregroundEpoch, this.appVisible, true);
    this.appVisible = true;
    this.runtimes.forEach((runtime) => runtime.onAppShown());
    this.sendAppLifecycleEvent('show');
  }

  onPageHide(keepAlive: boolean = false): void {
    this.appVisible = false;
    this.runtimes.forEach((runtime) => runtime.onAppHidden(keepAlive));
    this.sendAppLifecycleEvent(keepAlive ? 'hide_keep_alive' : 'hide');
  }

  disconnectForSessionEnd(reason: string): void {
    Array.from(this.runtimes.keys()).forEach((serverId) => this.removeRuntime(serverId, reason));
    this.processIdentity = undefined;
  }

  handleSharedEnhancedNotifyEvent(eventName: string, eventDataJson: string, envelopeJson: string): void {
    emitter.emit(eventName, {
      data: { eventName: eventName, stringify: eventDataJson.length > 0 ? eventDataJson : '{}' },
    });
    if (eventName !== ENHANCED_NOTIFY_EVENT_NAME) {
      emitter.emit(ENHANCED_NOTIFY_EVENT_NAME, {
        data: { eventName: ENHANCED_NOTIFY_EVENT_NAME, stringify: envelopeJson.length > 0 ? envelopeJson : '{}' },
      });
    }
  }

  private reconcile(params: KRRecord): void {
    const desiredServerIds = new Set<string>();
    const rawServerIds = (params['serverIds'] as Array<KRAny>) ?? [];
    rawServerIds.forEach((value) => {
      const serverId = `${value ?? ''}`.trim();
      if (serverId.length > 0) desiredServerIds.add(serverId);
    });
    const disconnectReason = (params['disconnectReason'] as string) ?? 'auth_invalid';
    if (desiredServerIds.size === 0) {
      Array.from(this.runtimes.keys()).forEach((serverId) => this.removeRuntime(serverId, disconnectReason));
      this.processIdentity = undefined;
      return;
    }
    const accessToken = ((params['accessToken'] as string) ?? '').trim();
    const environmentId = ((params['environmentId'] as string) ?? '').trim();
    const socketUrl = ((params['socketUrl'] as string) ?? DEFAULT_SOCKET_URL).trim();
    const scopeLeaseId = ((params['authorityScopeLeaseId'] as string) ?? '').trim();
    const principalId = ((params['authorityPrincipalId'] as string) ?? '').trim();
    const authGeneration = Number(params['authorityAuthGeneration'] ?? 0);
    const familyId = ((params['authorityConnectionFamilyId'] as string) ?? '').trim();
    if (accessToken.length === 0 || environmentId.length === 0 || scopeLeaseId.length === 0 || principalId.length === 0 || authGeneration <= 0 || familyId.length === 0) {
      console.warn(`[SlockOhosRealtime] process_reconcile_skip reason=invalid_authority serverCount=${desiredServerIds.size}`);
      Array.from(this.runtimes.keys()).forEach((serverId) => this.removeRuntime(serverId, 'invalid_process_authority'));
      this.processIdentity = undefined;
      return;
    }
    const nextIdentity: RealtimeProcessIdentity = {
      environmentId,
      socketUrl,
      principalId,
      authGeneration,
      connectionFamilyId: familyId,
      accessToken,
    };
    const identityChanged = this.processIdentity !== undefined && !this.sameIdentity(this.processIdentity, nextIdentity);
    if (identityChanged) {
      Array.from(this.runtimes.keys()).forEach((serverId) => this.removeRuntime(serverId, 'process_identity_replaced'));
    }
    this.processIdentity = nextIdentity;
    Array.from(this.runtimes.keys())
      .filter((serverId) => !desiredServerIds.has(serverId))
      .forEach((serverId) => this.removeRuntime(serverId, 'server_removed'));
    Array.from(desiredServerIds).sort().forEach((serverId) => {
      const runtime = this.runtimes.get(serverId) ?? this.createRuntime(serverId);
      runtime.connect({
        serverId,
        accessToken,
        socketUrl,
        lastSeq: runtime.currentLastSeq(),
        authorityScopeLeaseId: scopeLeaseId,
        authorityPrincipalId: principalId,
        authorityAuthGeneration: authGeneration,
        authorityConnectionFamilyId: familyId,
      }, params['forceReconnect'] === true || identityChanged);
    });
    console.info(`[SlockOhosRealtime] process_reconcile result=applied serverCount=${desiredServerIds.size} identityChanged=${identityChanged}`);
  }

  private reconcileLegacyConnect(params: KRRecord): void {
    const serverId = ((params['serverId'] as string) ?? '').trim();
    if (serverId.length === 0) return;
    const runtime = this.runtimes.get(serverId) ?? this.createRuntime(serverId);
    runtime.connect({
      serverId,
      accessToken: (params['accessToken'] as string) ?? '',
      socketUrl: (params['socketUrl'] as string) ?? DEFAULT_SOCKET_URL,
      lastSeq: Number(params['lastSeq'] ?? 0),
      authorityScopeLeaseId: (params['authorityScopeLeaseId'] as string) ?? '',
      authorityPrincipalId: (params['authorityPrincipalId'] as string) ?? '',
      authorityAuthGeneration: Number(params['authorityAuthGeneration'] ?? 0),
      authorityConnectionFamilyId: (params['authorityConnectionFamilyId'] as string) ?? '',
    }, params['forceReconnect'] === true);
  }

  private createRuntime(serverId: string): SlockOhosRealtimeRuntime {
    const runtime = new SlockOhosRealtimeRuntime(serverId, this, (data) => this.sendRealtimeNotify(data));
    this.runtimes.set(serverId, runtime);
    return runtime;
  }

  private removeRuntime(serverId: string, reason: string): void {
    const runtime = this.runtimes.get(serverId);
    if (runtime === undefined) return;
    this.runtimes.delete(serverId);
    runtime.disconnect(reason);
  }

  private disconnectForRealtimeCommand(reason: string, serverId: string): void {
    if (serverId.trim().length > 0) {
      this.removeRuntime(serverId.trim(), reason);
      return;
    }
    Array.from(this.runtimes.keys()).forEach((currentServerId) => this.removeRuntime(currentServerId, reason));
    this.processIdentity = undefined;
  }

  private installEnhancedNotifySink(): void {
    if (this.enhancedNotifySinkInstalled) return;
    registerSingleServiceProvider(ENHANCED_NOTIFY_SINK_SERVICE, new SlockOhosEnhancedNotifySink(this));
    this.enhancedNotifySinkInstalled = true;
  }

  private installEnhancedNotifyBridge(): void {
    if (this.enhancedNotifyCallback !== undefined) return;
    this.enhancedNotifyCallback = (event: emitter.EventData) => {
      const data = event.data as Record<string, string>;
      try {
        this.handleEnhancedNotify(JSON.parse(data.stringify ?? '{}') as KRRecord);
      } catch (error) {
        console.warn(`[SlockOhosRealtime] enhanced_notify_parse_failed message=${JSON.stringify(error)}`);
      }
    };
    emitter.on(ENHANCED_NOTIFY_EVENT_NAME, this.enhancedNotifyCallback);
  }

  private handleEnhancedNotify(envelope: KRRecord): void {
    const eventName = (envelope[ENHANCED_NOTIFY_KEY_EVENT_NAME] as string) ?? '';
    if (eventName !== GeneratedRealtimeContract.Notify.RoomsEventName) return;
    const source = (envelope[ENHANCED_NOTIFY_KEY_SOURCE] as string) ?? 'notify';
    const params = (envelope[ENHANCED_NOTIFY_KEY_DATA] as KRRecord) ?? {};
    const targetServerId = ((params[GeneratedRealtimeContract.PayloadKey.ServerId] as string) ?? '').trim();
    let runtime: SlockOhosRealtimeRuntime | undefined;
    if (targetServerId.length > 0) {
      runtime = this.runtimes.get(targetServerId);
    } else if (this.runtimes.size === 1) {
      runtime = this.runtimes.values().next().value as SlockOhosRealtimeRuntime;
    }
    if (runtime === undefined) {
      console.info(`[SlockOhosRealtime] room_command_skip reason=ambiguous_or_missing_server targetServerId=${targetServerId} runtimeCount=${this.runtimes.size}`);
      return;
    }
    runtime.handleRoomCommand(
      (params[GeneratedRealtimeContract.PayloadKey.Action] as string) ?? '',
      (params[GeneratedRealtimeContract.PayloadKey.ChannelId] as string) ?? '',
      source
    );
  }

  private sendAppLifecycleEvent(event: string): void {
    this.sendRealtimeNotify({
      action: 'appLifecycle',
      event: event,
      foregroundEpoch: this.foregroundEpoch,
      pageName: 'compose_slock_app_shell',
    });
  }

  private sendRealtimeNotify(data: KRRecord): void {
    try {
      const published = this.sharedIngress.publishEvent(JSON.stringify(data));
      if (!published) {
        console.warn('[SlockOhosRealtime] shared_ingress_failed reason=rejected');
      }
    } catch (error) {
      console.warn(`[SlockOhosRealtime] shared_ingress_failed reason=bridge_error message=${JSON.stringify(error)}`);
    }
  }

  private sameIdentity(left: RealtimeProcessIdentity, right: RealtimeProcessIdentity): boolean {
    return left.socketUrl === right.socketUrl &&
      left.environmentId === right.environmentId &&
      left.principalId === right.principalId &&
      left.connectionFamilyId === right.connectionFamilyId;
  }
}

export function nextSlockOhosForegroundEpoch(
  currentEpoch: number,
  currentVisible: boolean,
  nextVisible: boolean
): number {
  return nextVisible && !currentVisible ? currentEpoch + 1 : currentEpoch;
}

export function bootstrapRealtime(hostContext: SlockOhosRealtimeHostContext): SlockOhosRealtimeHostContext {
  hostContext.bootstrapRealtime();
  return hostContext;
}
