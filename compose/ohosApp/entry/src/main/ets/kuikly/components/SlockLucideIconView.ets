import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { SlockLucidePathEntry, slockLucidePathEntries } from '../generated/SlockLucidePathIcons';

const kName = 'name';
const kColor = 'color';
const kFill = 'fill';
const kStrokeWidth = 'strokeWidth';
const kVerticalOffsetDp = 'verticalOffsetDp';
const kFrame = 'frame';
const kRotationDegrees = 'rotationDegrees';
const kSpinning = 'spinning';
const kSpinDurationMs = 900;

@Observed
export class SlockLucideIconView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockLucideIconView';
  iconName: string = '';
  iconColor: ResourceColor = '#000000';
  iconFill: ResourceColor = '#00000000';
  iconFillEnabled: boolean = false;
  iconStrokeWidth: number = 0;
  iconVerticalOffsetDp: number = 0;
  iconRenderX: number = 0;
  iconRenderY: number = 0;
  iconRenderWidth: number = 0;
  iconRenderHeight: number = 0;
  iconRotationDegrees: number = 0;
  iconSpinning: boolean = false;
  iconSpinRotationDegrees: number = 0;
  renderRevision: number = 0;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    // ComponentContent is mounted once; prop updates below only mutate observed render state.
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockLucideIconView),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case kName:
        if (this.updateIconName(propValue as string)) {
          this.bumpRenderRevision();
        }
        return true;
      case kColor:
        this.updateIconColor(propValue as string);
        return true;
      case kFill:
        this.updateIconFill(propValue as string);
        return true;
      case kStrokeWidth:
        this.updateStrokeWidth(propValue);
        return true;
      case kVerticalOffsetDp:
        this.iconVerticalOffsetDp = normalizeVerticalOffset(propValue);
        this.bumpRenderRevision();
        return true;
      case kRotationDegrees:
        this.updateIconRotationDegrees(propValue);
        return true;
      case kSpinning:
        this.updateIconSpinning(propValue);
        return true;
      case kFrame:
        this.applyFrame(propValue);
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
  }

  didMoveToParentView(): void {
    this.bumpRenderRevision();
    if (this.iconSpinning) {
      this.startSpinAnimation();
    }
    this.updateArkUI();
  }

  sizeDidChanged(width: number, height: number): void {
    super.sizeDidChanged(width, height);
    this.updateRenderSize(width, height);
  }

  entries(): SlockLucidePathEntry[] {
    return slockLucidePathEntries(this.iconName);
  }

  strokeWidth(entry: SlockLucidePathEntry): number {
    return this.iconStrokeWidth > 0 ? this.iconStrokeWidth : entry.strokeWidth;
  }

  drawScale(entry: SlockLucidePathEntry): number {
    const width = this.iconRenderWidth > 0 ? this.iconRenderWidth : this.getViewWidth();
    const height = this.iconRenderHeight > 0 ? this.iconRenderHeight : this.getViewHeight();
    if (width <= 0 || height <= 0 || entry.viewportWidth <= 0 || entry.viewportHeight <= 0) {
      return 1;
    }
    return vp2px(Math.min(width / entry.viewportWidth, height / entry.viewportHeight));
  }

  rotationAngle(): number {
    return this.iconSpinning ? this.iconSpinRotationDegrees : this.iconRotationDegrees;
  }

  private bumpRenderRevision(): void {
    this.renderRevision = this.renderRevision >= 1000000 ? 1 : this.renderRevision + 1;
  }

  private updateIconName(value: string): boolean {
    const nextName = normalizeIconName(value);
    if (this.iconName === nextName) {
      return false;
    }
    this.iconName = nextName;
    return true;
  }

  private updateIconColor(value: string): boolean {
    const nextColor = normalizeColor(value);
    if (this.iconColor === nextColor) {
      return false;
    }
    this.iconColor = nextColor;
    return true;
  }

  private updateIconFill(value: string): boolean {
    const nextFillEnabled = hasColorValue(value);
    const nextFill = nextFillEnabled ? normalizeColor(value) : '#00000000';
    if (this.iconFillEnabled === nextFillEnabled && this.iconFill === nextFill) {
      return false;
    }
    this.iconFillEnabled = nextFillEnabled;
    this.iconFill = nextFill;
    return true;
  }

  private updateStrokeWidth(value: KRAny | KuiklyRenderCallback): boolean {
    const nextStrokeWidth = normalizeStrokeWidth(value);
    if (this.iconStrokeWidth === nextStrokeWidth) {
      return false;
    }
    this.iconStrokeWidth = nextStrokeWidth;
    return true;
  }

  private updateIconRotationDegrees(value: KRAny | KuiklyRenderCallback): boolean {
    const nextRotationDegrees = normalizeRotationDegrees(value);
    if (Math.abs(this.iconRotationDegrees - nextRotationDegrees) < 0.01) {
      return false;
    }
    this.iconRotationDegrees = nextRotationDegrees;
    return true;
  }

  private updateIconSpinning(value: KRAny | KuiklyRenderCallback): boolean {
    const nextSpinning = normalizeBoolean(value);
    if (this.iconSpinning === nextSpinning) {
      return false;
    }
    this.iconSpinning = nextSpinning;
    if (nextSpinning) {
      this.startSpinAnimation();
    } else {
      this.stopSpinAnimation();
    }
    return true;
  }

  private startSpinAnimation(): void {
    this.iconSpinRotationDegrees = 0;
    const uiContext = this.getUIContext() as UIContext;
    uiContext.animateTo(
      {
        duration: kSpinDurationMs,
        curve: Curve.Linear,
        iterations: -1,
        playMode: PlayMode.Normal,
      },
      () => {
        this.iconSpinRotationDegrees = 360;
      }
    );
  }

  private stopSpinAnimation(): void {
    const uiContext = this.getUIContext() as UIContext;
    uiContext.animateTo(
      {
        duration: 0,
        iterations: 0,
      },
      () => {
        this.iconSpinRotationDegrees = 0;
      }
    );
  }

  private updateRenderSize(width: number, height: number): boolean {
    const sizeChanged = this.iconRenderWidth !== width || this.iconRenderHeight !== height;
    if (!sizeChanged) {
      return false;
    }
    this.iconRenderWidth = width;
    this.iconRenderHeight = height;
    this.bumpRenderRevision();
    return true;
  }

  private updateRenderFrame(x: number, y: number, width: number, height: number): void {
    const positionChanged = this.iconRenderX !== x || this.iconRenderY !== y;
    if (positionChanged) {
      this.iconRenderX = x;
      this.iconRenderY = y;
    }
    const sizeChanged = this.updateRenderSize(width, height);
    if (positionChanged && !sizeChanged) {
      this.bumpRenderRevision();
    }
  }

  private applyFrame(value: KRAny | KuiklyRenderCallback): void {
    const frameText = typeof value === 'string' ? value : String(value);
    const fields = frameText.trim().split(/\s+/);
    if (fields.length < 4) {
      return;
    }
    const x = Number(fields[0]);
    const y = Number(fields[1]);
    const width = Number(fields[2]);
    const height = Number(fields[3]);
    if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
      this.updateRenderFrame(Number.isFinite(x) ? x : 0, Number.isFinite(y) ? y : 0, width, height);
    }
  }
}

@Component
export struct SlockLucideIcon {
  @ObjectLink renderView: SlockLucideIconView;

  build() {
    Stack({ alignContent: Alignment.Center }) {
      ForEach(this.renderView.entries(), (entry: SlockLucidePathEntry) => {
        Path({
          width: `${entry.viewportWidth}px`,
          height: `${entry.viewportHeight}px`,
          commands: entry.commands,
        })
          .width(`${entry.viewportWidth}px`)
          .height(`${entry.viewportHeight}px`)
          .fill(pathFillPaint(entry, this.renderView.iconColor, this.renderView.iconFill, this.renderView.iconFillEnabled))
          .stroke(pathStrokePaint(entry, this.renderView.iconColor))
          .strokeWidth(entry.strokeOpacity > 0 ? `${this.renderView.strokeWidth(entry)}px` : 0)
          .strokeLineCap(entry.strokeLineCap)
          .strokeLineJoin(entry.strokeLineJoin)
          .scale({ x: this.renderView.drawScale(entry), y: this.renderView.drawScale(entry) })
          .antiAlias(true)
      }, (entry: SlockLucidePathEntry) => `${this.renderView.renderRevision}:${this.renderView.iconName}:${entry.commands}:${entry.strokeWidth}`)
    }
      .alignContent(Alignment.Center)
      .position({ x: this.renderView.iconRenderX, y: this.renderView.iconRenderY + this.renderView.iconVerticalOffsetDp })
      .size({ width: this.renderView.iconRenderWidth, height: this.renderView.iconRenderHeight })
      .rotate({ angle: this.renderView.rotationAngle() })
      .alignSelf(ItemAlign.Start)
  }
}

function normalizeIconName(value: string): string {
  if (value === null || value === undefined) {
    return '';
  }
  return value
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/_/g, '-')
    .toLowerCase();
}

function normalizeColor(value: string): ResourceColor {
  if (value === null || value === undefined || value.length === 0) {
    return '#000000';
  }
  if (value.length === 9 && value.startsWith('#FF')) {
    return '#' + value.substring(3);
  }
  return value;
}

function hasColorValue(value: string): boolean {
  return value !== null && value !== undefined && value.length > 0;
}

function normalizeStrokeWidth(value: KRAny | KuiklyRenderCallback): number {
  if (typeof value === 'number') {
    return value > 0 ? value : 0;
  }
  if (typeof value === 'string') {
    const parsed = Number(value);
    return parsed > 0 ? parsed : 0;
  }
  return 0;
}

function normalizeVerticalOffset(value: KRAny | KuiklyRenderCallback): number {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === 'string') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function normalizeRotationDegrees(value: KRAny | KuiklyRenderCallback): number {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === 'string') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function normalizeBoolean(value: KRAny | KuiklyRenderCallback): boolean {
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    return value !== 0;
  }
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase();
    return normalized === 'true' || normalized === '1' || normalized === 'yes';
  }
  return false;
}

function resolvePathPaint(paint: string, currentColor: ResourceColor): ResourceColor {
  if (paint === 'currentColor') {
    return currentColor;
  }
  if (paint === null || paint === undefined || paint.length === 0 || paint === 'none' || paint === 'transparent') {
    return '#00000000';
  }
  return normalizeColor(paint);
}

function pathStrokePaint(entry: SlockLucidePathEntry, currentColor: ResourceColor): ResourceColor {
  if (entry.strokeOpacity <= 0) {
    return '#00000000';
  }
  return resolvePathPaint(entry.strokePaint, currentColor);
}

function pathFillPaint(entry: SlockLucidePathEntry, currentColor: ResourceColor, fillColor: ResourceColor, fillEnabled: boolean): ResourceColor {
  if (fillEnabled) {
    return fillColor;
  }
  if (entry.fillOpacity <= 0) {
    return '#00000000';
  }
  return resolvePathPaint(entry.fillPaint, currentColor);
}

@Builder
function createSlockLucideIconView(view: KuiklyRenderBaseView) {
  SlockLucideIcon({ renderView: view as SlockLucideIconView });
}
