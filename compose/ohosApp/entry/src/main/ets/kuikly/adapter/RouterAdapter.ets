import { IKRRouterAdapter, KRRecord } from '@kuikly-open/render';
import router from '@ohos.router';
import common from '@ohos.app.ability.common';
import { SlockOhosDiagnostics } from '../SlockOhosDiagnostics';

const REPLACE_CURRENT_PAGE = 'slockReplaceCurrentPage';
const ROUTER_LOG_TAG = 'SlockRouter';

export class RouterAdapter implements IKRRouterAdapter {
  openPage(context: common.UIAbilityContext, pageName: string, pageData: KRRecord): void {
    const routePageData = this.routePageData(pageName, pageData);
    const instanceId = this.stringValue(routePageData['instanceId']);
    const payloadBytes = this.stringValue(routePageData['payload']).length;
    const replaceCurrent = routePageData[REPLACE_CURRENT_PAGE] === true;
    SlockOhosDiagnostics.record(
      ROUTER_LOG_TAG,
      `open_page pageName=${pageName} instanceId=${instanceId} payloadBytes=${payloadBytes} replace=${replaceCurrent}`
    );
    if (replaceCurrent) {
      try {
        router.replaceUrl({
          url: 'pages/Index',
          params: {
            pageName,
            pageData: routePageData
          }
        });
        SlockOhosDiagnostics.record(ROUTER_LOG_TAG, `replace_dispatched pageName=${pageName}`);
      } catch (error) {
        SlockOhosDiagnostics.record(ROUTER_LOG_TAG, `replace_failed pageName=${pageName} error=${JSON.stringify(error)}`);
      }
      return;
    }
    try {
      router.pushUrl({
        url: 'pages/Index',
        params: {
          pageName,
          pageData: routePageData
        }
      });
      SlockOhosDiagnostics.record(ROUTER_LOG_TAG, `push_dispatched pageName=${pageName}`);
    } catch (error) {
      SlockOhosDiagnostics.record(ROUTER_LOG_TAG, `push_failed pageName=${pageName} error=${JSON.stringify(error)}`);
    }
  }

  closePage(context: common.UIAbilityContext): void {
    closeOhosPage(context);
  }

  private routePageData(pageName: string, pageData: KRRecord): KRRecord {
    const routePageData: KRRecord = {};
    Object.keys(pageData ?? {}).forEach((key: string) => {
      routePageData[key] = pageData[key];
    });
    routePageData['pageName'] = pageName;
    return routePageData;
  }

  private stringValue(value: Object | undefined): string {
    return typeof value === 'string' ? value : '';
  }
}

function closeOhosPage(context: common.UIAbilityContext): void {
  const routerLength = Number.parseInt(router.getLength(), 10);
  if (!Number.isNaN(routerLength) && routerLength > 1) {
    router.back();
    return;
  }
  context.terminateSelf().catch((error: Error) => {
    console.error(`KuiklyRender router terminateSelf failed ${error.message}`);
  });
}
