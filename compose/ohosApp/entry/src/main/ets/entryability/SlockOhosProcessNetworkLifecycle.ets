import Napi from 'libentry.so';
import { ApplicationStateChangeCallback, common } from '@kit.AbilityKit';

export type SlockOhosProcessNetworkVisibilitySink = (foreground: boolean) => number;
export type SlockOhosApplicationStateRegistrar = (callback: ApplicationStateChangeCallback) => void;

class SlockOhosApplicationStateCallback extends ApplicationStateChangeCallback {
  private readonly owner: SlockOhosProcessNetworkLifecycle;

  constructor(owner: SlockOhosProcessNetworkLifecycle) {
    super();
    this.owner = owner;
  }

  onApplicationForeground(): void {
    this.owner.onApplicationVisibilityChanged(true);
  }

  onApplicationBackground(): void {
    this.owner.onApplicationVisibilityChanged(false);
  }
}

/**
 * Process-owned NetworkKMM lifecycle bridge.
 *
 * ApplicationContext is the authority because UIAbility/window callbacks are page-local and can
 * produce false background edges during multiwindow, configuration, or another in-app ability.
 */
export class SlockOhosProcessNetworkLifecycle {
  private readonly sink: SlockOhosProcessNetworkVisibilitySink;
  private readonly callback: ApplicationStateChangeCallback;
  private installed: boolean = false;
  private foreground?: boolean;

  constructor(
    sink: SlockOhosProcessNetworkVisibilitySink =
      (foreground: boolean): number => Napi.updateProcessNetworkVisibility(foreground)
  ) {
    this.sink = sink;
    this.callback = new SlockOhosApplicationStateCallback(this);
  }

  install(applicationContext: common.ApplicationContext): boolean {
    return this.installWithRegistrar((callback: ApplicationStateChangeCallback): void => {
      applicationContext.on('applicationStateChange', callback);
    });
  }

  /** Injectable registration seam for deterministic host tests. */
  installWithRegistrar(register: SlockOhosApplicationStateRegistrar): boolean {
    if (this.installed) {
      return false;
    }
    register(this.callback);
    this.installed = true;
    return true;
  }

  /** Called only by the retained application-state callback, never by a UIAbility/window event. */
  onApplicationVisibilityChanged(foreground: boolean): number {
    if (this.foreground === foreground) {
      return 0;
    }
    const retiredRequestCount = this.sink(foreground);
    if (retiredRequestCount >= 0) {
      this.foreground = foreground;
    }
    return retiredRequestCount;
  }
}

const slockOhosProcessNetworkLifecycle = new SlockOhosProcessNetworkLifecycle();

export default slockOhosProcessNetworkLifecycle;
