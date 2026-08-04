import { hvigor, HvigorNode, HvigorPlugin } from '@ohos/hvigor';
import { execFileSync } from 'node:child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

interface KuiklyOhosConfig {
  projectPath: string;
  moduleName: string;
  libraryName?: string;
  ohosGradleSettings: string;
  soPath?: string;
  headerPath?: string;
  compilePluginEnabled?: string;
}

const DEFAULT_SO_PATH = 'entry/libs/arm64-v8a';
const DEFAULT_HEADER_PATH = 'entry/src/main/cpp';
const DEFAULT_LIBRARY_NAME = 'shared';

function resolveLibraryName(config: KuiklyOhosConfig): string {
  return config.libraryName || DEFAULT_LIBRARY_NAME;
}

export function kuiklyCompilePlugin(): HvigorPlugin {
  return {
    pluginId: 'slockKuiklyCompilePlugin',
    apply(node: HvigorNode) {
      if (os.platform() !== 'darwin') {
        return;
      }

      const config = readKuiklyOhosConfig();
      if (config.compilePluginEnabled === 'false') {
        return;
      }

      node.registerTask({
        name: 'kuikly_build',
        run: () => buildKuiklyOhos(config),
        postDependencies: ['default@PreBuild'],
      });

      node.registerTask({
        name: 'kuikly_clean',
        run: () => cleanKuiklyOhos(config),
        postDependencies: ['clean'],
      });
    },
  };
}

function cleanKuiklyOhos(config: KuiklyOhosConfig): void {
  validateConfig(config);

  const ohosAppPath = hvigor.getRootNode().getNodePath();
  const libraryName = resolveLibraryName(config);
  const soPath = config.soPath || DEFAULT_SO_PATH;
  const headerPath = config.headerPath || DEFAULT_HEADER_PATH;

  fs.rmSync(path.join(ohosAppPath, soPath, `lib${libraryName}.so`), { force: true });
  fs.rmSync(path.join(ohosAppPath, headerPath, `lib${libraryName}_api.h`), { force: true });
  fs.rmSync(path.join(ohosAppPath, 'entry/.cxx'), { force: true, recursive: true });

  runGradle(config, [`:${config.moduleName}:clean`]);
  runGradle(config, ['--stop']);
}

function buildKuiklyOhos(config: KuiklyOhosConfig): void {
  validateConfig(config);

  const parameter = hvigor.getParameter();
  const rawBuildMode = parameter.getExtParams().buildMode || 'debug';
  const buildMode = rawBuildMode.charAt(0).toUpperCase() + rawBuildMode.slice(1);
  const ohosAppPath = hvigor.getRootNode().getNodePath();
  const libraryName = resolveLibraryName(config);
  const soPath = config.soPath || DEFAULT_SO_PATH;
  const headerPath = config.headerPath || DEFAULT_HEADER_PATH;

  const destinationSo = path.join(ohosAppPath, soPath, `lib${libraryName}.so`);
  const destinationHeader = path.join(ohosAppPath, headerPath, `lib${libraryName}_api.h`);
  fs.rmSync(destinationSo, { force: true });
  fs.rmSync(destinationHeader, { force: true });

  // Kuikly fork artifacts intentionally shadow the public release under the
  // same Maven coordinate. Re-resolve here so a freshly published pinned fork
  // cannot be replaced by Gradle's older immutable-coordinate cache.
  runGradle(config, ['--refresh-dependencies', `:${config.moduleName}:link${buildMode}SharedOhosArm64`]);

  const outputDirectory = path.join(
    config.projectPath,
    config.moduleName,
    'build/bin/ohosArm64',
    `${rawBuildMode}Shared`,
  );
  const sourceSo = path.join(outputDirectory, `lib${libraryName}.so`);
  const sourceHeader = path.join(outputDirectory, `lib${libraryName}_api.h`);

  fs.mkdirSync(path.dirname(destinationSo), { recursive: true });
  fs.mkdirSync(path.dirname(destinationHeader), { recursive: true });
  fs.copyFileSync(sourceSo, destinationSo);
  fs.copyFileSync(sourceHeader, destinationHeader);
  verifyKuiklyAbi(ohosAppPath, destinationSo);
  verifyKuiklyForkProvenance(ohosAppPath, destinationSo);
}

function verifyKuiklyAbi(ohosAppPath: string, sharedSo: string): void {
  const renderLibraries = findFilesNamed(path.join(ohosAppPath, 'oh_modules'), 'libkuikly.so')
    .filter((candidate) => candidate.includes(`${path.sep}@kuikly-open${path.sep}render${path.sep}libs${path.sep}arm64-v8a${path.sep}`));
  if (renderLibraries.length !== 1) {
    throw new Error(`[kuiklyCompilePlugin] Expected exactly one resolved OHOS render library, found ${renderLibraries.length}`);
  }

  const verifier = path.resolve(ohosAppPath, '../../scripts/verify_kuikly_ohos_abi.sh');
  execFileSync(verifier, [sharedSo, renderLibraries[0]], { stdio: 'inherit' });
}

function verifyKuiklyForkProvenance(ohosAppPath: string, sharedSo: string): void {
  const verifier = path.resolve(ohosAppPath, '../../scripts/verify_kuikly_ohos_provenance.sh');
  execFileSync(verifier, [sharedSo], { stdio: 'inherit' });
}

function findFilesNamed(directory: string, fileName: string): string[] {
  if (!fs.existsSync(directory)) {
    return [];
  }

  const matches: string[] = [];
  fs.readdirSync(directory, { withFileTypes: true }).forEach((entry) => {
    const candidate = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      matches.push(...findFilesNamed(candidate, fileName));
    } else if (entry.isFile() && entry.name === fileName) {
      matches.push(candidate);
    }
  });
  return matches;
}

function runGradle(config: KuiklyOhosConfig, args: string[]): void {
  const gradleArgs = config.ohosGradleSettings
    ? ['-c', config.ohosGradleSettings, ...args]
    : args;

  try {
    execFileSync('./gradlew', gradleArgs, {
      cwd: config.projectPath,
      stdio: 'inherit',
    });
  } catch (error) {
    throw new Error(`Kuikly Gradle command failed: ${gradleArgs.join(' ')}`, { cause: error });
  }
}

function validateConfig(config: KuiklyOhosConfig): void {
  if (!config.projectPath || !config.moduleName || !config.ohosGradleSettings) {
    throw new Error('[kuiklyCompilePlugin] Missing required local.properties configuration');
  }
}

function readKuiklyOhosConfig(): KuiklyOhosConfig {
  const rootPath = hvigor.getRootNode().getNodePath();
  const localPropertiesPath = path.join(rootPath, 'local.properties');
  if (!fs.existsSync(localPropertiesPath)) {
    throw new Error('[kuiklyCompilePlugin] local.properties does not exist');
  }

  const values = new Map<string, string>();
  fs.readFileSync(localPropertiesPath, 'utf-8')
    .split(/\r?\n/)
    .forEach((line) => {
      const separator = line.indexOf('=');
      if (separator <= 0) {
        return;
      }
      values.set(line.slice(0, separator).trim(), line.slice(separator + 1).trim());
    });

  return {
    projectPath: values.get('kuikly.projectPath') || '',
    moduleName: values.get('kuikly.moduleName') || '',
    libraryName: values.get('kuikly.libraryName'),
    ohosGradleSettings: values.get('kuikly.ohosGradleSettings') || '',
    soPath: values.get('kuikly.soPath'),
    headerPath: values.get('kuikly.headerPath'),
    compilePluginEnabled: values.get('kuikly.compilePluginEnabled'),
  };
}
