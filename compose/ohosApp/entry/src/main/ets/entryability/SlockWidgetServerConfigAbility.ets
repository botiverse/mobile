import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import hilog from '@ohos.hilog';
import UIAbility from '@ohos.app.ability.UIAbility';
import Want from '@ohos.app.ability.Want';
import window from '@ohos.window';
import { formInfo } from '@kit.FormKit';

export const WIDGET_CONFIG_FORM_ID_KEY = 'slockWidgetConfigFormId';
export const WIDGET_CONFIG_FORM_NAME_KEY = 'slockWidgetConfigFormName';

export default class SlockWidgetServerConfigAbility extends UIAbility {
  onCreate(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    this.publishWant(want);
  }

  onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    this.publishWant(want);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/WidgetServerConfig', (err, data) => {
      if (err.code) {
        hilog.error(
          0x0000,
          'SlockWidgetConfig',
          'Failed to load widget config page. Cause: %{public}s',
          JSON.stringify(err) ?? ''
        );
        return;
      }
      hilog.info(
        0x0000,
        'SlockWidgetConfig',
        'Loaded widget config page. Data: %{public}s',
        JSON.stringify(data) ?? ''
      );
    });
  }

  private publishWant(want: Want): void {
    const formId = this.wantString(
      want,
      formInfo.FormParam.IDENTITY_KEY,
      'formId',
      'form_id',
      'ohos.extra.param.key.form_identity'
    );
    const formName = this.wantString(
      want,
      formInfo.FormParam.NAME_KEY,
      'formName',
      'form_name',
      'ohos.extra.param.key.form_name'
    );
    AppStorage.setOrCreate(WIDGET_CONFIG_FORM_ID_KEY, formId);
    AppStorage.setOrCreate(WIDGET_CONFIG_FORM_NAME_KEY, formName);
  }

  private wantString(want: Want, ...keys: string[]): string {
    for (const key of keys) {
      const value = want.parameters?.[key];
      if (typeof value === 'string' && value.length > 0) {
        return value;
      }
      if (typeof value === 'number') {
        return `${value}`;
      }
    }
    return '';
  }
}
