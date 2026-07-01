import { invoke } from '@kuiklybase/knoi';

export interface SlockRealtimeConnectDecision {
  shouldConnect: boolean;
  resumeSeq: number;
  sessionChanged: boolean;
  logMessage: string | null;
}

export interface SlockRealtimeRoomCommandDecision {
  shouldEmit: boolean;
  eventName: string | null;
  payload: string | null;
  channelId: string | null;
  joinedChannelAction: string;
  logMessage: string;
}

export interface SlockRealtimeRawDecision {
  shouldFanOut: boolean;
  serverId: string | null;
  eventName: string;
  rawPayload: string;
  logMessage: string;
}

class SlockOhosRealtimeSharedBridge {
  connectDecision(
    serverId: string,
    accessToken: string,
    requestedLastSeq: number,
    forceReconnect: boolean,
    connectionStateName: string
  ): SlockRealtimeConnectDecision {
    return this.invokeJson<SlockRealtimeConnectDecision>(
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
    return this.invokeJson<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiJoinChannelDecision',
      channelId,
      reason
    );
  }

  roomCommandDecision(action: string, channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeJson<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiRoomCommandDecision',
      action,
      channelId,
      reason
    );
  }

  rawDecision(eventName: string, rawPayload: string): SlockRealtimeRawDecision {
    return this.invokeJson<SlockRealtimeRawDecision>(
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

  private invokeJson<T>(name: string, ...args: Object[]): T {
    return this.invokeValue<T>(name, ...args);
  }

  private invokeValue<T>(name: string, ...args: Object[]): T {
    const result = invoke<T>(name, ...args);
    if (typeof result === 'string') {
      return JSON.parse(result) as T;
    }
    return result;
  }

  private invokeVoid(name: string): void {
    invoke<void>(name);
  }
}

const slockOhosRealtimeSharedBridge = new SlockOhosRealtimeSharedBridge();

export default slockOhosRealtimeSharedBridge;
