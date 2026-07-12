import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';

const ADAPTIVE_PANE_EVENT = 'slockAdaptivePaneChanged';

export interface SlockOhosAdaptivePaneSnapshot {
  widthPx: number;
  heightPx: number;
  density: number;
  posture: string;
}

/**
 * EntryAbility owns WindowStage and fold APIs while Kuikly owns the shared pager. Retaining the
 * latest fact lets a WindowStage callback arrive before the render controller is available.
 */
export class SlockOhosAdaptivePaneBridge {
  private controller?: KRNativeRenderController;
  private latest?: SlockOhosAdaptivePaneSnapshot;

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
    this.flush();
  }

  publish(snapshot: SlockOhosAdaptivePaneSnapshot): void {
    if (snapshot.widthPx <= 0 || snapshot.heightPx <= 0 || snapshot.density <= 0) {
      return;
    }
    if (this.isSameSnapshot(snapshot, this.latest)) {
      return;
    }
    this.latest = snapshot;
    this.flush();
  }

  private flush(): void {
    if (this.controller === undefined || this.latest === undefined) {
      return;
    }
    const snapshot = this.latest;
    this.controller.sendEvent(ADAPTIVE_PANE_EVENT, {
      widthPx: snapshot.widthPx,
      heightPx: snapshot.heightPx,
      density: snapshot.density,
      posture: snapshot.posture,
    } as KRRecord);
  }

  private isSameSnapshot(
    left: SlockOhosAdaptivePaneSnapshot,
    right?: SlockOhosAdaptivePaneSnapshot
  ): boolean {
    return right !== undefined &&
      left.widthPx === right.widthPx &&
      left.heightPx === right.heightPx &&
      left.density === right.density &&
      left.posture === right.posture;
  }
}

const slockOhosAdaptivePaneBridge = new SlockOhosAdaptivePaneBridge();

export default slockOhosAdaptivePaneBridge;
