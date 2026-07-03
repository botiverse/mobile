import { appTasks } from '@ohos/hvigor-ohos-plugin';
import * as fs from 'fs';
import * as path from 'path';

let fileData: { signingConfig?: object } | null = null;
const dataPath = path.resolve(__dirname, './data.json');
if (fs.existsSync(dataPath)) {
    fileData = require(dataPath);
}

const signingConfig = getSigningConfig();

export default {
    system: appTasks,  /* Built-in plugin of Hvigor. It cannot be modified. */
    plugins:[],        /* Custom plugin to extend the functionality of Hvigor. */
    config: {
        ohos: {
            overrides: {
                ...(signingConfig ? { signingConfig } : {})
            }
        }
    }
}

function getSigningConfig() {
    if (fileData?.signingConfig) {
        return {
            type: 'HarmonyOS',
            material: fileData.signingConfig,
        };
    }

    const certFile = process.env.SIGNING_CERT;
    const profileFile = process.env.SIGNING_PROFILE;
    const keyFile = process.env.SIGNING_KEY;
    const keyPassword = process.env.KEY_PASSWORD;
    const storePassword = process.env.KEYSTORE_PASSWORD;

    if (certFile && profileFile && keyFile) {
        return {
            type: 'HarmonyOS',
            material: {
                certpath: certFile,
                profile: profileFile,
                keyAlias: process.env.KEY_ALIAS ?? 'debugKey',
                keyPassword,
                signAlg: 'SHA256withECDSA',
                storeFile: keyFile,
                storePassword,
            },
        };
    }

    return null;
}
