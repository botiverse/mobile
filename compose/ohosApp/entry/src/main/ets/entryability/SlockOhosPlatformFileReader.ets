import { BusinessError } from '@kit.BasicServicesKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';

export const SLOCK_OHOS_PLATFORM_FILE_READER_SERVICE = 'slockOhosPlatformFileReader';

export class SlockOhosPlatformFileReader {
  readFile(fileRef: string): Record<string, Object> {
    if (fileRef.length === 0) {
      return this.error('missing_file_ref', 'Missing file ref');
    }
    try {
      const file = fs.openSync(fileRef, fs.OpenMode.READ_ONLY);
      try {
        const stat = fs.statSync(file.fd);
        const bodyBytes = new ArrayBuffer(stat.size);
        fs.readSync(file.fd, bodyBytes);
        return {
          'status': 'ok',
          'code': 200,
          'bodyBase64': new util.Base64Helper().encodeToStringSync(new Uint8Array(bodyBytes)),
          'sizeBytes': stat.size
        };
      } finally {
        fs.closeSync(file);
      }
    } catch (error) {
      const businessError = error as BusinessError;
      return this.error(
        'attachment_read_failed',
        businessError.message ?? `${error}`
      );
    }
  }

  private error(errorCode: string, message: string): Record<string, Object> {
    return {
      'status': 'error',
      'code': 400,
      'errorCode': errorCode,
      'errorBodyText': message
    };
  }
}
