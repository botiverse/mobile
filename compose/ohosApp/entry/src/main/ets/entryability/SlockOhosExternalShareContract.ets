export class SlockOhosExternalShareLaunch {
  intent: string;
  externalShareToken: string;

  constructor(intent: string, externalShareToken: string) {
    this.intent = intent;
    this.externalShareToken = externalShareToken;
  }
}
