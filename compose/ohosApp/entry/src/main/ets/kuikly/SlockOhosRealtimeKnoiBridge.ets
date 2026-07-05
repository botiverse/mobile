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

class SlockOhosRealtimeKnoiBridge {
  connectDecision(
    serverId: string,
    accessToken: string,
    requestedLastSeq: number,
    forceReconnect: boolean,
    connectionStateName: string
  ): SlockRealtimeConnectDecision {
    return this.invokeValue<SlockRealtimeConnectDecision>(
      'slockRealtimeKnoiConnectDecision',
      serverId,
      accessToken,
      requestedLastSeq,
      forceReconnect,
      connectionStateName
    );
  }

  disconnectForSessionEnd(reason: string): boolean {
    return this.invokeValue<boolean>(
      'slockRealtimeKnoiDisconnectForSessionEnd',
      reason
    );
  }

  joinChannelDecision(channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeValue<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiJoinChannelDecision',
      channelId,
      reason
    );
  }

  roomCommandDecision(action: string, channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeValue<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiRoomCommandDecision',
      action,
      channelId,
      reason
    );
  }

  rawDecision(eventName: string, rawPayload: string): SlockRealtimeRawDecision {
    return this.invokeValue<SlockRealtimeRawDecision>(
      'slockRealtimeKnoiRawDecision',
      eventName,
      rawPayload
    );
  }

  onAppShown(): void {
    this.invokeVoid('slockRealtimeKnoiOnAppShown');
  }

  onAppHidden(): void {
    this.invokeVoid('slockRealtimeKnoiOnAppHidden');
  }

  sharedTransportConnect(socketUrl: string, serverId: string, accessToken: string): void {
    this.invokeVoid(
      'slockRealtimeKnoiSharedTransportConnect',
      socketUrl,
      serverId,
      accessToken
    );
  }

  sharedTransportDisconnect(): void {
    this.invokeVoid('slockRealtimeKnoiSharedTransportDisconnect');
  }

  sharedTransportEmit(eventName: string, payload: Record<string, Object>): boolean {
    return this.invokeValue<boolean>(
      'slockRealtimeKnoiSharedTransportEmit',
      eventName,
      payload
    );
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
