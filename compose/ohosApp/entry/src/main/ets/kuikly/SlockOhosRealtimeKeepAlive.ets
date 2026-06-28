import common from '@ohos.app.ability.common';
import Want from '@ohos.app.ability.Want';
import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import wantAgent, { WantAgent } from '@ohos.wantAgent';

const LOG_TAG = 'SlockOhosRealtimeKeepAlive';
const NOTIFICATION_ID = 73001;

class SlockOhosRealtimeKeepAlive {
  private running: boolean = false;

  start(context: common.UIAbilityContext, source: string = 'unknown'): void {
    this.startInternal(context, source).catch((error: Object) => {
      console.warn(`[${LOG_TAG}] start_failed source=${source} message=${this.safeErrorMessage(error)}`);
    });
  }

  stop(context: common.UIAbilityContext, reason: string = 'unknown'): void {
    this.stopInternal(context, reason).catch((error: Object) => {
      console.warn(`[${LOG_TAG}] stop_failed reason=${reason} message=${this.safeErrorMessage(error)}`);
    });
  }

  private async startInternal(context: common.UIAbilityContext, source: string): Promise<void> {
    if (this.running) {
      console.info(`[${LOG_TAG}] start_skip reason=already_running source=${source}`);
      return;
    }
    const agent = await this.buildLaunchWantAgent(context);
    await backgroundTaskManager.startBackgroundRunning(
      context,
      backgroundTaskManager.BackgroundMode.DATA_TRANSFER,
      agent
    );
    this.running = true;
    console.info(`[${LOG_TAG}] continuous_task_started source=${source}`);
  }

  private async stopInternal(context: common.UIAbilityContext, reason: string): Promise<void> {
    if (this.running) {
      await backgroundTaskManager.stopBackgroundRunning(context);
      this.running = false;
      console.info(`[${LOG_TAG}] continuous_task_stopped reason=${reason}`);
    }
  }

  private async buildLaunchWantAgent(context: common.UIAbilityContext): Promise<WantAgent> {
    const launchWant: Want = {
      bundleName: context.abilityInfo.bundleName,
      abilityName: context.abilityInfo.name,
    };
    return wantAgent.getWantAgent({
      wants: [launchWant],
      operationType: wantAgent.OperationType.START_ABILITY,
      requestCode: NOTIFICATION_ID,
      wantAgentFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG],
    });
  }

  private safeErrorMessage(error: Object): string {
    try {
      const encoded = JSON.stringify(error);
      if (encoded !== undefined && encoded.length > 0 && encoded !== '{}') {
        return encoded;
      }
    } catch (_err) {
      // Fall back to String(error).
    }
    return String(error);
  }
}

const slockOhosRealtimeKeepAlive = new SlockOhosRealtimeKeepAlive();

export default slockOhosRealtimeKeepAlive;
