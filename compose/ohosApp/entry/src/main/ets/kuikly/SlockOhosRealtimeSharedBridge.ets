import { invoke } from '@kuiklybase/knoi';
import Napi from 'libentry.so';

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
  private knoiDisabled: boolean = false;

  connectDecision(
    serverId: string,
    accessToken: string,
    requestedLastSeq: number,
    forceReconnect: boolean,
    connectionStateName: string
  ): SlockRealtimeConnectDecision {
    return this.invokeJson<SlockRealtimeConnectDecision>(
      'slockRealtimeKnoiConnectDecision',
      () => Napi.slockRealtimeConnectDecision(
        serverId,
        accessToken,
        requestedLastSeq,
        forceReconnect,
        connectionStateName
      ),
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
      () => Napi.slockRealtimeDisconnectForSessionEnd(reason),
      reason
    );
  }

  joinChannelDecision(channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeJson<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiJoinChannelDecision',
      () => Napi.slockRealtimeJoinChannelDecision(channelId, reason),
      channelId,
      reason
    );
  }

  roomCommandDecision(action: string, channelId: string, reason: string): SlockRealtimeRoomCommandDecision {
    return this.invokeJson<SlockRealtimeRoomCommandDecision>(
      'slockRealtimeKnoiRoomCommandDecision',
      () => Napi.slockRealtimeRoomCommandDecision(action, channelId, reason),
      action,
      channelId,
      reason
    );
  }

  rawDecision(eventName: string, rawPayload: string): SlockRealtimeRawDecision {
    return this.invokeJson<SlockRealtimeRawDecision>(
      'slockRealtimeKnoiRawDecision',
      () => Napi.slockRealtimeRawDecision(eventName, rawPayload),
      eventName,
      rawPayload
    );
  }

  onAppShown(): void {
    this.invokeVoid('slockRealtimeKnoiOnAppShown', () => Napi.slockRealtimeOnAppShown());
  }

  onAppHidden(): void {
    this.invokeVoid('slockRealtimeKnoiOnAppHidden', () => Napi.slockRealtimeOnAppHidden());
  }

  private invokeJson<T>(name: string, fallback: () => T, ...args: Object[]): T {
    return this.invokeValue<T>(name, fallback, ...args);
  }

  private invokeValue<T>(name: string, fallback: () => T, ...args: Object[]): T {
    if (!this.knoiDisabled) {
      try {
        const result = invoke<T>(name, ...args);
        if (typeof result === 'string') {
          return JSON.parse(result) as T;
        }
        return result;
      } catch (error) {
        this.knoiDisabled = true;
        console.warn(`[SlockOhosRealtime] knoi_call_failed name=${name} error=${JSON.stringify(error)}`);
      }
    }
    return fallback();
  }

  private invokeVoid(name: string, fallback: () => void): void {
    if (!this.knoiDisabled) {
      try {
        invoke<void>(name);
        return;
      } catch (error) {
        this.knoiDisabled = true;
        console.warn(`[SlockOhosRealtime] knoi_call_failed name=${name} error=${JSON.stringify(error)}`);
      }
    }
    fallback();
  }
}

const slockOhosRealtimeSharedBridge = new SlockOhosRealtimeSharedBridge();

export default slockOhosRealtimeSharedBridge;
