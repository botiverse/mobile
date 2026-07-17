import { invoke } from '@kuiklybase/knoi';

export interface SlockRealtimeConnectDecision {
  shouldConnect: boolean;
  resumeSeq: number;
  sessionChanged: boolean;
  // Always a string ("" = nothing to log). The Kotlin side never sends null:
  // KNOI marshals a Kotlin null map value to `undefined`, which a `!== null`
  // guard misses, so `.length` on it would throw.
  logMessage: string;
}

export interface SlockRealtimeRoomCommandDecision {
  shouldEmit: boolean;
  // Non-null strings ("" = absent). null must not cross the KNOI bridge (it
  // arrives as `undefined`); validity is gated by the authoritative `shouldEmit`
  // boolean, so these are guaranteed real strings whenever shouldEmit is true.
  eventName: string;
  payload: string;
  // "" = no channel; presence-gated by length below.
  channelId: string;
  joinedChannelAction: string;
  logMessage: string;
}

export interface SlockRealtimeRawDecision {
  shouldFanOut: boolean;
  // "" = absent; validity gated by the authoritative `shouldFanOut` boolean.
  serverId: string;
  eventName: string;
  rawPayload: string;
  logMessage: string;
}

export interface SlockRealtimeCursorAdvanceDecision {
  shouldAdvance: boolean;
  resumeSeq: number;
  reason: string;
}

class SlockOhosRealtimeKnoiBridge {
  connectDecision(
    runtimeId: string,
    serverId: string,
    accessToken: string,
    requestedLastSeq: number,
    forceReconnect: boolean,
    connectionStateName: string
  ): SlockRealtimeConnectDecision {
    return this.invokeValue<SlockRealtimeConnectDecision>(
      'slockRealtimeKnoiConnectDecision',
      runtimeId,
      serverId,
      accessToken,
      requestedLastSeq,
      forceReconnect,
      connectionStateName
    );
  }

  disconnectForSessionEnd(runtimeId: string, reason: string): boolean {
    return this.invokeValue<boolean>(
      'slockRealtimeKnoiDisconnectForSessionEnd',
      runtimeId,
      reason
    );
  }

  joinChannelDecision(runtimeId: string, channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeValue<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiJoinChannelDecision',
      runtimeId,
      channelId,
      reason
    );
  }

  roomCommandDecision(runtimeId: string, action: string, channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeValue<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiRoomCommandDecision',
      runtimeId,
      action,
      channelId,
      reason
    );
  }

  rawDecision(runtimeId: string, connectionServerId: string, eventName: string, rawPayload: string): SlockRealtimeRawDecision {
    return this.invokeValue<SlockRealtimeRawDecision>(
      'slockRealtimeKnoiRawDecision',
      runtimeId,
      connectionServerId,
      eventName,
      rawPayload
    );
  }

  advanceAppliedSeq(
    runtimeId: string,
    currentServerId: string,
    currentScopeLeaseId: string,
    currentPrincipalId: string,
    currentAuthGeneration: number,
    currentConnectionFamilyId: string,
    requestServerId: string,
    requestScopeLeaseId: string,
    requestPrincipalId: string,
    requestAuthGeneration: number,
    requestConnectionFamilyId: string,
    appliedSeq: number
  ): SlockRealtimeCursorAdvanceDecision {
    return this.invokeValue<SlockRealtimeCursorAdvanceDecision>(
      'slockRealtimeKnoiAdvanceAppliedSeq',
      runtimeId,
      currentServerId,
      currentScopeLeaseId,
      currentPrincipalId,
      currentAuthGeneration,
      currentConnectionFamilyId,
      requestServerId,
      requestScopeLeaseId,
      requestPrincipalId,
      requestAuthGeneration,
      requestConnectionFamilyId,
      appliedSeq
    );
  }

  currentResumeSeq(runtimeId: string): number {
    return this.invokeValue<number>('slockRealtimeKnoiCurrentResumeSeq', runtimeId);
  }

  onAppShown(runtimeId: string): void {
    this.invokeVoid('slockRealtimeKnoiOnAppShown', runtimeId);
  }

  onAppHidden(runtimeId: string): void {
    this.invokeVoid('slockRealtimeKnoiOnAppHidden', runtimeId);
  }

  releaseRuntime(runtimeId: string): void {
    this.invokeVoid('slockRealtimeKnoiReleaseRuntime', runtimeId);
  }

  private invokeValue<T>(name: string, ...args: Object[]): T {
    return invoke<T>(name, ...args);
  }

  private invokeVoid(name: string, ...args: Object[]): void {
    invoke<void>(name, ...args);
  }
}

const slockOhosRealtimeKnoiBridge = new SlockOhosRealtimeKnoiBridge();

export default slockOhosRealtimeKnoiBridge;
