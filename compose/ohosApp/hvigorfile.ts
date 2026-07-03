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
        if (!isSigningProfileCompatible(fileData.signingConfig)) {
            return null;
        }
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
        if (!isSigningProfileCompatible({ profile: profileFile })) {
            return null;
        }
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

function isSigningProfileCompatible(material: object): boolean {
    const profileFile = (material as { profile?: string }).profile;
    if (!profileFile) {
        return true;
    }

    const expectedBundleName = readAppBundleName();
    const profileBundleName = readProfileBundleName(profileFile);
    if (!expectedBundleName || !profileBundleName || expectedBundleName === profileBundleName) {
        return true;
    }

    console.warn(
        `Skip OHOS signing profile for bundle ${profileBundleName}; app bundle is ${expectedBundleName}.`
    );
    return false;
}

function readAppBundleName(): string | null {
    const appJsonPath = path.resolve(__dirname, './AppScope/app.json5');
    if (!fs.existsSync(appJsonPath)) {
        return null;
    }

    const content = fs.readFileSync(appJsonPath, 'utf8');
    return content.match(/["']bundleName["']\s*:\s*["']([^"']+)["']/)?.[1] ?? null;
}

function readProfileBundleName(profileFile: string): string | null {
    if (!fs.existsSync(profileFile)) {
        return null;
    }

    const content = fs.readFileSync(profileFile, 'utf8');
    return content.match(/["']bundle-name["']\s*:\s*["']([^"']+)["']/)?.[1] ?? null;
}
