import { IKRLogAdapter } from '@kuikly-open/render';
import hilog from '@ohos.hilog';
import { SlockOhosDiagnostics } from '../SlockOhosDiagnostics';

export class LogAdapter implements IKRLogAdapter {
  d(tag: string, msg: string): void {
    hilog.debug(0x0000, tag, '%{public}s', msg);
    SlockOhosDiagnostics.record(tag, msg);
  }

  i(tag: string, msg: string): void {
    hilog.info(0x0000, tag, '%{public}s', msg);
    SlockOhosDiagnostics.record(tag, msg);
  }

  e(tag: string, msg: string): void {
    hilog.error(0x0000, tag, '%{public}s', msg);
    SlockOhosDiagnostics.record(tag, msg);
  }
}
