import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';

const EXTERNAL_SHARE_LAUNCH_EVENT = 'slockExternalShareLaunch';

interface SlockOhosExternalShareLaunch {
  intent: string;
  externalShareToken: string;
}

/** Delivers a token-only warm launch to an already-mounted shared pager exactly once per token. */
export class SlockOhosExternalShareLaunchBridge {
  private controller?: KRNativeRenderController;
  private pending?: SlockOhosExternalShareLaunch;
  private deliveredToken: string = '';

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
    this.flush();
  }

  publish(launch: SlockOhosExternalShareLaunch): void {
    if (launch.externalShareToken.length === 0 || launch.externalShareToken === this.deliveredToken) return;
    this.pending = launch;
    this.flush();
  }

  private flush(): void {
    if (this.controller === undefined || this.pending === undefined) return;
    const launch = this.pending;
    this.controller.sendEvent(EXTERNAL_SHARE_LAUNCH_EVENT, {
      intent: launch.intent,
      externalShareToken: launch.externalShareToken
    } as KRRecord);
    this.deliveredToken = launch.externalShareToken;
    this.pending = undefined;
  }
}

const slockOhosExternalShareLaunchBridge = new SlockOhosExternalShareLaunchBridge();

export default slockOhosExternalShareLaunchBridge;
