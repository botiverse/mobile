import { hapTasks } from '@ohos/hvigor-ohos-plugin';
import { kuiklyCompilePlugin, kuiklyCopyAssetsPlugin } from 'kuikly-ohos-compile-plugin';
import * as fs from 'fs';
import * as path from 'path';

syncSlockFontRawfiles();

export default {
    system: hapTasks,  /* Built-in plugin of Hvigor. It cannot be modified. */
    plugins:[kuiklyCompilePlugin()]         /* Custom plugin to extend the functionality of Hvigor. */
}

function syncSlockFontRawfiles(): void {
    const sourceDir = path.resolve(__dirname, 'src/main/resources/resfile/fonts');
    const rawfileDir = path.resolve(__dirname, 'src/main/resources/rawfile/fonts');

    if (!fs.existsSync(sourceDir)) {
        return;
    }

    fs.mkdirSync(rawfileDir, { recursive: true });
    fs.readdirSync(sourceDir)
        .filter((fileName: string) => fileName.endsWith('.ttf'))
        .forEach((fileName: string) => {
            fs.copyFileSync(
                path.join(sourceDir, fileName),
                path.join(rawfileDir, fileName),
            );
        });
}
