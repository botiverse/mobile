export const installKnoiAsyncLog: () => number;
export const initKuikly: (imageDiagnostic?: (message: string, failed: boolean) => void) => number;
export const setImageHttp3Enabled: (enabled: boolean) => boolean;
export const updateProcessNetworkVisibility: (foreground: boolean) => number;

declare const _default: {
  installKnoiAsyncLog: typeof installKnoiAsyncLog;
  initKuikly: typeof initKuikly;
  setImageHttp3Enabled: typeof setImageHttp3Enabled;
  updateProcessNetworkVisibility: typeof updateProcessNetworkVisibility;
};

export default _default;
