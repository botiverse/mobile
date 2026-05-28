import { IKRRouterAdapter, KRRecord } from '@kuikly-open/render';
import router from '@ohos.router';
import common from '@ohos.app.ability.common';

const REPLACE_CURRENT_PAGE = 'slockReplaceCurrentPage';

export class RouterAdapter implements IKRRouterAdapter {
  openPage(context: common.UIAbilityContext, pageName: string, pageData: KRRecord): void {
    if (pageData[REPLACE_CURRENT_PAGE] === true) {
      router.replaceUrl({
        url: 'pages/Index',
        params: {
          pageName,
          pageData
        }
      });
      return;
    }
    router.pushUrl({
      url: 'pages/Index',
      params: {
        pageName,
        pageData
      }
    });
  }

  closePage(context: common.UIAbilityContext): void {
    closeOhosPage(context);
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
