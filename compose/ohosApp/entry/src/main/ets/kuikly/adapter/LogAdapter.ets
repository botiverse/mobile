import { IKRLogAdapter } from '@kuikly-open/render';
import { SlockOhosDiagnostics } from '../SlockOhosDiagnostics';

export class LogAdapter implements IKRLogAdapter {
  d(tag: string, msg: string): void {
    SlockOhosDiagnostics.recordAsync(tag, msg, Date.now(), 'debug');
  }

  i(tag: string, msg: string): void {
    SlockOhosDiagnostics.recordAsync(tag, msg, Date.now(), 'info');
  }

  e(tag: string, msg: string): void {
    SlockOhosDiagnostics.recordAsync(tag, msg, Date.now(), 'error');
  }
}
