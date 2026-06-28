import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { fileUri } from '@kit.CoreFileKit';

const kKeysText = 'keysText';
const kLabelsText = 'labelsText';
const kIconsText = 'iconsText';
const kEnabledText = 'enabledText';
const kSelectedKey = 'selectedKey';
const kBottomSafeArea = 'bottomSafeArea';
const kSelect = 'select';
const kSeparator = '\u001F';

const kDockHeight = 56;
const kDockSideMargin = 18;
const kDockHostHeight = 92;
const kItemHeight = 40;
const kIconSize = 17;
const kDockCornerRadius = 28;
const kDockBorderColor = '#66FFFFFF';
const kDockBackgroundColor = '#CCFFFFFF';
const kDockShadowColor = '#26000000';
const kSelectedItemBackground = '#24FFD440';

interface SlockHdsTabItem {
  key: string;
  label: string;
  icon: string;
  enabled: boolean;
}

@Observed
export class SlockOhosHdsTabBarView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockOhosHdsTabBarView';
  keysText: string = '';
  labelsText: string = '';
  iconsText: string = '';
  enabledText: string = '';
  selectedKey: string = '';
  bottomSafeArea: number = 0;
  selectCallback: KuiklyRenderCallback | null = null;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockOhosHdsTabBarView),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case kKeysText:
        this.keysText = normalizeText(propValue);
        return true;
      case kLabelsText:
        this.labelsText = normalizeText(propValue);
        return true;
      case kIconsText:
        this.iconsText = normalizeText(propValue);
        return true;
      case kEnabledText:
        this.enabledText = normalizeText(propValue);
        return true;
      case kSelectedKey:
        this.selectedKey = normalizeText(propValue);
        return true;
      case kBottomSafeArea:
        this.bottomSafeArea = normalizeNumber(propValue);
        return true;
      case kSelect:
        this.selectCallback = propValue as KuiklyRenderCallback;
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
  }

  items(): SlockHdsTabItem[] {
    const keys = splitText(this.keysText);
    const labels = splitText(this.labelsText);
    const icons = splitText(this.iconsText);
    const enabled = splitText(this.enabledText);
    return keys.map((key: string, index: number): SlockHdsTabItem => {
      return {
        key: key,
        label: labels[index] ?? key,
        icon: icons[index] ?? '',
        enabled: enabled[index] !== '0',
      };
    }).filter((item: SlockHdsTabItem) => item.key.length > 0);
  }

  selectedIndex(): number {
    const items = this.items();
    const index = items.findIndex((item: SlockHdsTabItem) => item.key === this.selectedKey);
    return index >= 0 ? index : 0;
  }

  selectIndex(index: number) {
    const items = this.items();
    if (index < 0 || index >= items.length) {
      return;
    }
    const item = items[index];
    if (!item.enabled) {
      return;
    }
    this.selectedKey = item.key;
    this.selectCallback?.({
      key: item.key,
      index: index,
    });
  }

  iconResource(name: string): ResourceStr | null {
    const iconName = normalizeIconName(name);
    if (iconName.length === 0) {
      return null;
    }
    return fileUri.getUriFromPath(getContext().resourceDir + '/lucide/' + iconName + '.svg');
  }
}

@Component
export struct SlockOhosHdsTabBar {
  @ObjectLink renderView: SlockOhosHdsTabBarView;

  private itemColor(item: SlockHdsTabItem, index: number): ResourceColor {
    if (!item.enabled) {
      return '#66000000';
    }
    return this.renderView.selectedIndex() === index ? '#FF000000' : '#FF737373';
  }

  private itemFontWeight(index: number): FontWeight {
    return this.renderView.selectedIndex() === index ? FontWeight.Bold : FontWeight.Medium;
  }

  private itemIconOpacity(item: SlockHdsTabItem, index: number): number {
    if (!item.enabled) {
      return 0.4;
    }
    return this.renderView.selectedIndex() === index ? 1 : 0.45;
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Row() {
        ForEach(this.renderView.items(), (item: SlockHdsTabItem, index: number) => {
          this.tabBarItem(item, index)
        }, (item: SlockHdsTabItem) => item.key)
      }
      .width('100%')
      .height(`${kDockHeight}vp`)
      .margin({
        left: kDockSideMargin,
        right: kDockSideMargin,
      })
      .padding({
        left: 8,
        right: 8,
        top: 8,
        bottom: 8,
      })
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .borderRadius(kDockCornerRadius)
      .border({ width: 1, color: kDockBorderColor })
      .backgroundColor(kDockBackgroundColor)
      .shadow({
        radius: 18,
        color: kDockShadowColor,
        offsetX: 0,
        offsetY: 8,
      })
    }
    .width('100%')
    .height(`${kDockHostHeight}vp`)
  }

  @Builder
  tabBarItem(item: SlockHdsTabItem, index: number) {
    Column() {
      Image(this.renderView.iconResource(item.icon))
        .width(kIconSize)
        .height(kIconSize)
        .objectFit(ImageFit.Contain)
        .interpolation(ImageInterpolation.High)
        .supportSvg2(true)
        .opacity(this.itemIconOpacity(item, index))
      Text(item.label.toUpperCase())
        .fontSize(9)
        .fontWeight(this.itemFontWeight(index))
        .fontColor(this.itemColor(item, index))
        .lineHeight(12)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .layoutWeight(1)
    .height(kItemHeight)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .borderRadius(16)
    .backgroundColor(this.renderView.selectedIndex() === index ? kSelectedItemBackground : '#00FFFFFF')
    .onClick(() => {
      this.renderView.selectIndex(index);
    })
  }
}

function splitText(value: string): string[] {
  if (value.length === 0) {
    return [];
  }
  return value.split(kSeparator);
}

function normalizeText(value: KRAny | KuiklyRenderCallback): string {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
}

function normalizeNumber(value: KRAny | KuiklyRenderCallback): number {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0;
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

@Builder
function createSlockOhosHdsTabBarView(view: KuiklyRenderBaseView) {
  SlockOhosHdsTabBar({ renderView: view as SlockOhosHdsTabBarView });
}
