import { ComponentContent } from '@kit.ArkUI';
import { UIContext } from '@ohos.arkui.UIContext';
import {
  IKRVideoViewAdapter,
  IKRVideoViewController,
  IKRVideoViewListener,
  KRValue,
  KRVideoPlayState,
  KRVideoView,
  KRVideoViewContentMode,
  KuiklyRenderBaseView,
  KuiklyRenderCallback
} from '@kuikly-open/render';

@Component
struct SlockVideoView {
  @State videoViewController: SlockKRVideoViewController | null = null;
  @State muted: boolean = false;
  @State rate: number = 1;
  @State imageFit: ImageFit = ImageFit.Contain;
  totalTime: number = 0;

  build() {
    Video({
      controller: this.videoViewController?.viewController,
      src: this.videoViewController?.videoSrc,
      currentProgressRate: this.rate
    })
      .muted(this.muted)
      .objectFit(this.imageFit)
      // OHOS phase 2: the shared side hides its minimal overlay
      // (slockPlatformUsesNativeVideoController = true), so the native ArkUI
      // control bar (play/scrub/fullscreen) owns the surface. Pin it on
      // explicitly rather than rely on the platform default.
      .controls(true)
      .onStart(() => {
        this.videoViewController?.onPlayStateDidChanged(KRVideoPlayState.Playing);
      })
      .onPause(() => {
        this.videoViewController?.onPlayStateDidChanged(KRVideoPlayState.Paused);
      })
      .onFinish(() => {
        this.videoViewController?.onPlayStateDidChanged(KRVideoPlayState.PlayEnd);
      })
      .onStop(() => {
        this.videoViewController?.onPlayStateDidChanged(KRVideoPlayState.PlayEnd);
      })
      .onError(() => {
        this.videoViewController?.onPlayStateDidChanged(KRVideoPlayState.Failed);
      })
      .onPrepared((params) => {
        this.videoViewController?.onFirstFrameDidDisplay();
        this.totalTime = params.duration;
      })
      .onUpdate((params) => {
        this.videoViewController?.onPlayTimeDidChanged(params.time, this.totalTime);
      });
  }

  aboutToAppear(): void {
    this.videoViewController?.stateController.setMutedCallback((muted: boolean) => {
      this.muted = muted;
    });
    this.videoViewController?.stateController.setRateCallback((rate: number) => {
      this.rate = rate;
    });
    this.videoViewController?.stateController.setResizeModeCallback((imageFit: ImageFit) => {
      this.imageFit = imageFit;
    });
  }

  aboutToDisappear(): void {
    this.videoViewController?.stateController.delete();
  }
}

@Builder
function createSlockVideoView(view: KuiklyRenderBaseView) {
  SlockVideoView({
    videoViewController: (view as KRVideoView).controller! as SlockKRVideoViewController
  });
}

class SlockVideoViewStateController {
  private mutedCallback: ((muted: boolean) => void) | undefined = () => {
  };
  private rateCallback: ((rate: number) => void) | undefined = () => {
  };
  private imageFitCallback: ((imageFit: ImageFit) => void) | undefined = () => {
  };

  setMutedCallback(callback: (muted: boolean) => void): void {
    this.mutedCallback = callback;
  }

  setRateCallback(callback: (rate: number) => void): void {
    this.rateCallback = callback;
  }

  setResizeModeCallback(callback: (imageFit: ImageFit) => void): void {
    this.imageFitCallback = callback;
  }

  setMuted(muted: boolean): void {
    this.mutedCallback?.(muted);
  }

  setRate(rate: number): void {
    this.rateCallback?.(rate);
  }

  setResizeMode(imageFit: ImageFit): void {
    this.imageFitCallback?.(imageFit);
  }

  delete(): void {
    this.mutedCallback = undefined;
    this.rateCallback = undefined;
    this.imageFitCallback = undefined;
  }
}

class SlockKRVideoViewController implements IKRVideoViewController {
  viewController: VideoController = new VideoController();
  stateController: SlockVideoViewStateController = new SlockVideoViewStateController();
  videoSrc: string = '';
  private listeners = new Set<IKRVideoViewListener>();

  setProp(propKey: string, propValue: KRValue | KuiklyRenderCallback): boolean {
    if (propKey === 'seekToTimeMs') {
      const seekToTimeMs = slockVideoSeekTimeMs(propValue);
      if (seekToTimeMs !== null) {
        this.seekToTime(seekToTimeMs);
      }
      return true;
    }
    return false;
  }

  addListener(listener: IKRVideoViewListener): void {
    this.listeners.add(listener);
  }

  removeListener(listener: IKRVideoViewListener): void {
    this.listeners.delete(listener);
  }

  setSrc(src: string): void {
    if (src.startsWith('assets://')) {
      const trailing = src.substring(8);
      this.videoSrc = getContext().resourceDir + trailing;
      return;
    }
    this.videoSrc = src;
  }

  preplay(): void {
    this.viewController.setCurrentTime(0.04);
  }

  play(): void {
    this.viewController.start();
  }

  pause(): void {
    this.viewController.pause();
  }

  stop(): void {
    this.viewController.stop();
  }

  setVideoContentMode(videoViewContentMode: KRVideoViewContentMode): void {
    if (videoViewContentMode === KRVideoViewContentMode.Contain) {
      this.stateController.setResizeMode(ImageFit.Contain);
    } else if (videoViewContentMode === KRVideoViewContentMode.Cover) {
      this.stateController.setResizeMode(ImageFit.Cover);
    } else if (videoViewContentMode === KRVideoViewContentMode.Stretch) {
      this.stateController.setResizeMode(ImageFit.Fill);
    }
  }

  setMuted(muted: boolean): void {
    this.stateController.setMuted(muted);
  }

  setRate(rate: number): void {
    this.stateController.setRate(rate);
  }

  seekToTime(seekToTimeMs: number): void {
    this.viewController.setCurrentTime(seekToTimeMs / 1000);
  }

  onPlayStateDidChanged = (state: KRVideoPlayState) => {
    this.listeners.forEach((listener) => {
      listener.onPlayStateDidChanged?.(state, this);
    });
  };

  onPlayTimeDidChanged = (currentTime: number, totalTime: number) => {
    this.listeners.forEach((listener) => {
      listener.onPlayTimeDidChanged?.(currentTime, totalTime, this);
    });
  };

  onFirstFrameDidDisplay = () => {
    this.listeners.forEach((listener) => {
      listener.onFirstFrameDidDisplay?.(this);
    });
  };
}

function slockVideoSeekTimeMs(value: KRValue | KuiklyRenderCallback): number | null {
  if (typeof value === 'number') {
    return value;
  }
  if (typeof value === 'string') {
    const rawTime = value.substring(value.lastIndexOf(':') + 1);
    const parsed = Number(rawTime);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

export class SlockKRVideoAdapter implements IKRVideoViewAdapter {
  createController(): IKRVideoViewController {
    return new SlockKRVideoViewController();
  }

  createVideoView(ctx: UIContext, view: KuiklyRenderBaseView): ComponentContent<KuiklyRenderBaseView> {
    return new ComponentContent<KuiklyRenderBaseView>(
      ctx,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockVideoView),
      view
    );
  }
}
