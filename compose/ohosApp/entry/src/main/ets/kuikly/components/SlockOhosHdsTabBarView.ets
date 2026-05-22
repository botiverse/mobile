import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { DividerMode, HdsTabs, HdsTabsController } from '@kit.UIDesignKit';

const kKeysText = 'keysText';
const kLabelsText = 'labelsText';
const kIconsText = 'iconsText';
const kEnabledText = 'enabledText';
const kSelectedKey = 'selectedKey';
const kBottomSafeArea = 'bottomSafeArea';
const kSeparator = '\u001F';

interface SlockHdsTabItem {
  key: string;
  label: string;
  icon: string;
  enabled: boolean;
}

@Observed
export class SlockOhosHdsTabBarView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockOhosHdsTabBarView';
  controller: HdsTabsController = new HdsTabsController();
  keysText: string = '';
  labelsText: string = '';
  iconsText: string = '';
  enabledText: string = '';
  selectedKey: string = '';
  bottomSafeArea: number = 0;

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
        this.controller.changeIndex(this.selectedIndex());
        return true;
      case kBottomSafeArea:
        this.bottomSafeArea = normalizeNumber(propValue);
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
}

@Component
export struct SlockOhosHdsTabBar {
  @ObjectLink renderView: SlockOhosHdsTabBarView;

  build() {
    HdsTabs({ index: this.renderView.selectedIndex(), controller: this.renderView.controller }) {
      ForEach(this.renderView.items(), (item: SlockHdsTabItem, index: number) => {
        TabContent() {
          Column() {
            Blank()
              .width('100%')
              .height(1)
          }
        }
        .tabBar(this.tabBarItem(item, index))
      }, (item: SlockHdsTabItem) => item.key)
    }
    .vertical(false)
    .barPosition(BarPosition.End)
    .barMode(BarMode.Fixed)
    .scrollable(false)
    .animationDuration(240)
    .barHeight(60)
    .barOverlap(true)
    .barBackgroundColor('#CCFFFFFF')
    .barBackgroundBlurStyle(BlurStyle.Thin)
    .divider({ mode: DividerMode.NONE })
    .width('100%')
    .height(`${92 + this.renderView.bottomSafeArea}vp`)
    .padding({
      left: 18,
      right: 18,
      bottom: 28 + this.renderView.bottomSafeArea,
    })
    .backgroundColor('#33FFFFFF')
  }

  @Builder
  tabBarItem(item: SlockHdsTabItem, index: number) {
    Column() {
      Text(tabIcon(item.icon))
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(item.enabled ? '#FF000000' : '#66000000')
        .lineHeight(20)
      Text(item.label.toUpperCase())
        .fontSize(9)
        .fontWeight(FontWeight.Bold)
        .fontColor(item.enabled ? '#FF000000' : '#66000000')
        .lineHeight(12)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .height(54)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .borderRadius(18)
    .backgroundColor(this.renderView.selectedIndex() === index ? '#FFFFE352' : '#00FFFFFF')
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

function tabIcon(name: string): string {
  switch (name) {
    case 'house':
      return '⌂';
    case 'square-check-big':
      return '✓';
    case 'users':
      return '♟';
    case 'settings':
      return '⚙';
    default:
      return '•';
  }
}

@Builder
function createSlockOhosHdsTabBarView(view: KuiklyRenderBaseView) {
  SlockOhosHdsTabBar({ renderView: view as SlockOhosHdsTabBarView });
}
