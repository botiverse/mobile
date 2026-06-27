export const initKuikly: () => number;

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
  joinedChannelAction: 'none' | 'join' | 'leave' | string;
  logMessage: string;
}

export interface SlockRealtimeRawDecision {
  shouldFanOut: boolean;
  serverId: string | null;
  eventName: string;
  rawPayload: string;
  logMessage: string;
}

export const slockRealtimeConnectDecision: (
  serverId: string,
  accessToken: string,
  requestedLastSeq: number,
  forceReconnect: boolean,
  connectionStateName: string
) => SlockRealtimeConnectDecision;

export const slockRealtimeDisconnectForSessionEnd: (reason: string) => boolean;

export const slockRealtimeJoinChannelDecision: (
  channelId: string,
  reason: string
) => SlockRealtimeRoomCommandDecision;

export const slockRealtimeRoomCommandDecision: (
  action: string,
  channelId: string,
  reason: string
) => SlockRealtimeRoomCommandDecision;

export const slockRealtimeRawDecision: (
  eventName: string,
  rawPayload: string
) => SlockRealtimeRawDecision;

export const slockRealtimeOnAppShown: () => void;

export const slockRealtimeOnAppHidden: () => void;

declare const _default: {
  initKuikly: typeof initKuikly;
  slockRealtimeConnectDecision: typeof slockRealtimeConnectDecision;
  slockRealtimeDisconnectForSessionEnd: typeof slockRealtimeDisconnectForSessionEnd;
  slockRealtimeJoinChannelDecision: typeof slockRealtimeJoinChannelDecision;
  slockRealtimeRoomCommandDecision: typeof slockRealtimeRoomCommandDecision;
  slockRealtimeRawDecision: typeof slockRealtimeRawDecision;
  slockRealtimeOnAppShown: typeof slockRealtimeOnAppShown;
  slockRealtimeOnAppHidden: typeof slockRealtimeOnAppHidden;
};

export default _default;
