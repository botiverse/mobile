import pasteboard from '@ohos.pasteboard';

export class SlockOhosClipboard {
  static copyText(content: string): void {
    const pasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, content);
    pasteboard.getSystemPasteboard().setDataSync(pasteData);
  }
}
