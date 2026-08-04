import { HvigorNode, HvigorPlugin } from '@ohos/hvigor';
import { OhosHapContext, OhosPluginId } from '@ohos/hvigor-ohos-plugin';

const CALLBACK_HOST = 'oauth';
const CALLBACK_PATH = '/callback';
const VIEW_DATA_ACTION = 'ohos.want.action.viewData';

interface OauthUri {
  scheme?: string;
  host?: string;
  path?: string;
}

interface Skill {
  actions?: string[];
  uris?: OauthUri[];
}

interface Ability {
  name?: string;
  skills?: Skill[];
}

interface ModuleJson {
  module?: {
    abilities?: Ability[];
  };
}

export function slockOhosOAuthCallbackPlugin(): HvigorPlugin {
  return {
    pluginId: 'slockOhosOAuthCallbackPlugin',
    apply(node: HvigorNode): void {
      const context = node.getContext(OhosPluginId.OHOS_HAP_PLUGIN) as OhosHapContext | undefined;
      if (context === undefined) {
        throw new Error('[slockOhosOAuthCallbackPlugin] OHOS HAP context is unavailable');
      }
      const buildMode = context.getBuildMode().trim().toLowerCase();
      if (buildMode !== 'debug' && buildMode !== 'release') {
        throw new Error(`[slockOhosOAuthCallbackPlugin] Unsupported installable build mode: ${buildMode}`);
      }
      const callbackScheme = buildMode === 'release' ? 'raft' : 'raft-debug';
      const moduleJson = context.getModuleJsonOpt() as ModuleJson;
      const entryAbility = moduleJson.module?.abilities?.find((ability) => ability.name === 'EntryAbility');
      const callbackSkills = entryAbility?.skills?.filter((skill) =>
        skill.actions?.includes(VIEW_DATA_ACTION) === true &&
        skill.uris?.some((uri) => uri.host === CALLBACK_HOST && uri.path === CALLBACK_PATH) === true
      ) ?? [];
      if (callbackSkills.length !== 1) {
        throw new Error(
          `[slockOhosOAuthCallbackPlugin] Expected one OAuth callback skill, found ${callbackSkills.length}`
        );
      }
      callbackSkills[0].uris = [{ scheme: callbackScheme, host: CALLBACK_HOST, path: CALLBACK_PATH }];
      context.setModuleJsonOpt(moduleJson);
    },
  };
}
