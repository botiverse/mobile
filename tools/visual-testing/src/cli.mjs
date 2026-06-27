#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const packageRoot = path.resolve(import.meta.dirname, "..");
const repoRoot = path.resolve(process.env.SLOCK_VISUAL_REPO_ROOT || findRepoRoot(process.cwd()));
const defaultReactRepoRoot = path.join(repoRoot, "third_party", "slock-source");
const reactRepoRoot = path.resolve(
  initialFlagValue(["react-repo-dir", "reactRepoDir"]) ||
    process.env.SLOCK_REACT_REPO_DIR ||
    process.env.SLOCK_VISUAL_REACT_REPO_ROOT ||
    defaultReactRepoRoot,
);
const webRoot = path.join(reactRepoRoot, "packages", "web");
const srcRoot = path.join(webRoot, "src");
const sharedVisualRoot = path.join(packageRoot, "shared");
const artifactRoot = path.join(repoRoot, "artifacts", "visual-testing");
const resultRoot = path.join(repoRoot, "visual-testing-results");
const siteRoot = path.join(repoRoot, "artifacts", "visual-testing-site");
const sharedTokensPath = path.join(sharedVisualRoot, "sharedTokens.json");
const sharedCasesPath = path.join(sharedVisualRoot, "sharedCases.json");
const tokenAuditClassificationPath = path.join(sharedVisualRoot, "tokenAuditColorClassifications.json");
const defaultManifestPath = path.join(artifactRoot, "visual-testing-cases.json");
const inventoryPath = path.join(artifactRoot, "react-component-inventory.json");
const providerSpecPath = path.join(artifactRoot, "provider-spec.json");
const sharpImageDiffScriptPath = path.join(packageRoot, "src", "sharp-image-diff.mjs");
const analysisRoot = path.join(resultRoot, "analysis");
const defaultAndroidRepoDir = repoRoot;
const pixelPerfectPassThreshold = 0.99;
const pixelPerfectBasicPassThreshold = 0.96;
const componentFixtureCaptureType = "component-fixture";
const realScreenCaptureType = "real-screen";
const captureTypeValues = new Set([componentFixtureCaptureType, realScreenCaptureType]);
const captureTypeLabels = {
  [componentFixtureCaptureType]: "Components Parity",
  [realScreenCaptureType]: "Screens Parity",
};
const captureTypeSegments = {
  [componentFixtureCaptureType]: "components",
  [realScreenCaptureType]: "screens",
};
const defaultKmpTokenPath = path.join(
  "compose",
  "shared",
  "src",
  "commonMain",
  "kotlin",
  "ai",
  "slock",
  "compose",
  "SlockDesignTokens.kt"
);
const tokenKmpColorMap = {
  brutalYellow: "BrandYellow",
  brutalPink: "AccentPink",
  brutalLavender: "AccentLavender",
  brutalCream: "SurfaceCream",
  brutalBlack: "ShadowBlack",
  brutalCyan: "AccentCyan",
  brutalOrange: "AccentOrange",
  brutalLime: "AccentLime",
  brutalRed: "AccentRed",
  brutalStone: "AccentStone",
  surfaceWhite: "SurfaceWhite",
  surfaceCream: "SurfaceCream",
  appBgLightBlue: "AppBgLightBlue",
  emptyStateGray: "EmptyStateGray",
  textBlack: "TextBlack",
  textMutedGray: "TextMutedGray",
  borderBlack: "BorderBlack",
  shadowBlack: "ShadowBlack",
};
const tokenAuditAllowedReactFiles = new Set([
  "packages/web/src/index.css",
  "packages/web/src/visual-testing/sharedTokens.json",
  "packages/web/src/visual-testing/tokenAuditColorClassifications.json",
  "packages/web/src/visual-testing/VisualComponentCases.tsx",
  "packages/web/src/pages/PaletteAuditPage.tsx",
  "packages/web/src/components/agent/PixelAvatar.tsx",
]);
const tokenAuditAllowedKmpFiles = new Set([
  defaultKmpTokenPath,
  path.join("compose", "shared", "src", "commonMain", "kotlin", "ai", "slock", "compose", "TokenPaletteVisualPage.kt"),
  path.join("compose", "shared", "src", "commonMain", "kotlin", "ai", "slock", "compose", "ReverseLazyColumnDemoPage.kt"),
]);

function initialFlagValue(names) {
  const wanted = new Set(names.map((name) => `--${name}`));
  for (let index = 2; index < process.argv.length - 1; index += 1) {
    if (wanted.has(process.argv[index]) && !process.argv[index + 1].startsWith("--")) return process.argv[index + 1];
  }
  return "";
}

const defaultCases = [
  {
    id: "home.left-rail.default",
    surface: "home",
    reactPathHint: "packages/web/src/components/layout/LeftRail.tsx",
    androidCaseHint: "compose_home_left_rail_default",
    selector: "[data-testid='left-rail-tab-chat']",
  },
  {
    id: "home.server-switcher.unread",
    surface: "home",
    reactPathHint: "packages/web/src/components/ui/ServerSwitcherMenu.tsx",
    androidCaseHint: "compose_home_server_switcher_unread",
    selector: "[data-testid='desktop-server-switcher-menu']",
  },
  {
    id: "home.sidebar.channels-expanded",
    surface: "home",
    reactPathHint: "packages/web/src/components/layout/Sidebar.tsx",
    androidCaseHint: "compose_home_sidebar_channels_expanded",
    selector: "[data-testid='sidebar-section-toggle-channels']",
  },
  {
    id: "thread.message.markdown-chips",
    surface: "thread",
    reactPathHint: "packages/web/src/components/message/MessageItem.tsx",
    androidCaseHint: "compose_thread_message_markdown_chips",
    selector: "[data-testid='message-thread-replies-badge']",
  },
  {
    id: "navigation.tabbar.badges",
    surface: "navigation",
    reactPathHint: "packages/web/src/components/layout/mobile/MobileShell.tsx",
    androidCaseHint: "compose_bottom_tabbar_badges",
    selector: "[data-visual-case='navigation.tabbar.badges']",
  },
];

function parseArgs(argv) {
  const args = { command: argv[2] || "all", flags: {} };
  for (let i = 3; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith("--")) {
      args.flags[key] = true;
    } else {
      args.flags[key] = next;
      i += 1;
    }
  }
  return args;
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    stdio: options.stdio || "inherit",
    cwd: options.cwd || repoRoot,
    env: options.env || process.env,
    encoding: options.encoding,
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed with status ${result.status}`);
  }
  return result;
}

function findRepoRoot(startDir) {
  let current = path.resolve(startDir);
  while (true) {
    if (fs.existsSync(path.join(current, "pnpm-workspace.yaml")) || fs.existsSync(path.join(current, ".git"))) {
      return current;
    }
    const parent = path.dirname(current);
    if (parent === current) return startDir;
    current = parent;
  }
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function writeJson(file, value) {
  ensureDir(path.dirname(file));
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function parseTimestamp(value) {
  if (value === null || value === undefined || value === "") return null;
  const raw = String(value).trim();
  if (/^\d+$/.test(raw)) {
    const numeric = Number(raw);
    if (!Number.isFinite(numeric)) return null;
    const millis = numeric < 100000000000 ? numeric * 1000 : numeric;
    const date = new Date(millis);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? null : date;
}

function utcTimestampText(value) {
  const date = parseTimestamp(value);
  if (!date) return value ? String(value) : "unknown";
  return date.toISOString().replace("T", " ").replace(/\.\d{3}Z$/, " UTC");
}

function localizedTimestampHtml(value, options = {}) {
  const date = parseTimestamp(value);
  const label = options.label ? `${options.label} ` : "";
  if (!date) return `${xmlEscape(label)}${xmlEscape(value ? String(value) : "unknown")}`;
  const iso = date.toISOString();
  return `${xmlEscape(label)}<time class="localizedTime" datetime="${xmlEscape(iso)}" data-timestamp="${xmlEscape(iso)}" title="${xmlEscape(String(value))}">${xmlEscape(utcTimestampText(iso))}</time>`;
}

function localizedTimeScript() {
  return `<script>
    (() => {
      const formatter = new Intl.DateTimeFormat(undefined, {
        year: "numeric",
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        timeZoneName: "short",
      });
      for (const node of document.querySelectorAll("time.localizedTime[data-timestamp]")) {
        const date = new Date(node.dataset.timestamp);
        if (!Number.isNaN(date.getTime())) node.textContent = formatter.format(date);
      }
    })();
  </script>`;
}

function overlayComparisonScript() {
  return `<script>
    (() => {
      const loadImage = (src) => new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = () => resolve(image);
        image.onerror = () => reject(new Error("Unable to load overlay image: " + src));
        image.src = src;
      });
      const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
      const sliderValue = (panel, selector, fallback) => {
        const value = Number(panel.querySelector(selector)?.value ?? fallback);
        return Number.isFinite(value) ? clamp(value / 100, 0, 1) : fallback / 100;
      };
      const snapValue = (value, size) => {
        const snapPoints = [0, -size, -size / 2, size / 2, size];
        const threshold = Math.max(4, size * 0.02);
        for (const point of snapPoints) {
          if (Math.abs(value - point) <= threshold) return point;
        }
        return value;
      };
      const snappedOffset = (panel, x, y, width, height) => {
        if (!panel.querySelector("[data-overlay-snap]")?.checked) return { x, y };
        return {
          x: snapValue(x, width),
          y: snapValue(y, height),
        };
      };

      const drawOverlay = (panel, baselineImage, currentImage) => {
        const canvas = panel.querySelector("canvas[data-overlay-canvas]");
        if (!canvas) return;
        const context = canvas.getContext("2d");
        if (!context) return;
        const width = Number(panel.dataset.overlayWidth) || baselineImage.naturalWidth || currentImage.naturalWidth;
        const height = Number(panel.dataset.overlayHeight) || baselineImage.naturalHeight || currentImage.naturalHeight;
        const mode = panel.querySelector("[data-overlay-mode]")?.value || "blend";
        const baselineOpacity = sliderValue(panel, "[data-overlay-baseline-opacity]", 100);
        const currentOpacity = sliderValue(panel, "[data-overlay-opacity]", 50);
        const split = Math.max(0, Math.min(1, Number(panel.dataset.overlaySplit || 0.5)));
        const offsetX = Number(panel.dataset.overlayOffsetX || 0);
        const offsetY = Number(panel.dataset.overlayOffsetY || 0);
        panel.dataset.overlayMode = mode;
        canvas.width = width;
        canvas.height = height;
        context.clearRect(0, 0, width, height);
        context.globalAlpha = baselineOpacity;
        context.globalCompositeOperation = "source-over";
        context.drawImage(baselineImage, 0, 0, width, height);
        context.save();
        context.globalAlpha = mode === "split" ? 1 : currentOpacity;
        context.globalCompositeOperation = mode === "difference" ? "difference" : "source-over";
        if (mode === "split") {
          context.beginPath();
          context.rect(0, 0, Math.round(width * split), height);
          context.clip();
          context.globalAlpha = 1;
        }
        context.drawImage(currentImage, mode === "align" ? offsetX : 0, mode === "align" ? offsetY : 0, width, height);
        context.restore();
        if (mode === "split") {
          const x = Math.round(width * split);
          context.save();
          context.strokeStyle = "#141111";
          context.lineWidth = Math.max(2, Math.round(width / 160));
          context.beginPath();
          context.moveTo(x, 0);
          context.lineTo(x, height);
          context.stroke();
          context.fillStyle = "#FFD440";
          context.strokeStyle = "#141111";
          context.lineWidth = Math.max(1, Math.round(width / 300));
          context.beginPath();
          context.arc(x, Math.max(20, Math.round(height * 0.08)), Math.max(8, Math.round(width / 36)), 0, Math.PI * 2);
          context.fill();
          context.stroke();
          context.restore();
        }
        if (mode === "align") {
          context.save();
          context.strokeStyle = "rgba(20, 17, 17, 0.35)";
          context.lineWidth = Math.max(1, Math.round(width / 300));
          context.setLineDash([6, 6]);
          context.beginPath();
          context.moveTo(width / 2, 0);
          context.lineTo(width / 2, height);
          context.moveTo(0, height / 2);
          context.lineTo(width, height / 2);
          context.stroke();
          context.setLineDash([]);
          context.strokeStyle = "#141111";
          context.fillStyle = "#FFD440";
          context.lineWidth = Math.max(2, Math.round(width / 180));
          context.strokeRect(offsetX, offsetY, width, height);
          context.beginPath();
          context.arc(clamp(offsetX + width / 2, 12, width - 12), clamp(offsetY + height * 0.08, 12, height - 12), Math.max(8, Math.round(width / 40)), 0, Math.PI * 2);
          context.fill();
          context.stroke();
          context.restore();
        }
        const baselineValueLabel = panel.querySelector("[data-overlay-baseline-opacity-value]");
        if (baselineValueLabel) baselineValueLabel.textContent = Math.round(baselineOpacity * 100) + "%";
        const valueLabel = panel.querySelector("[data-overlay-opacity-value]");
        if (valueLabel) valueLabel.textContent = Math.round(currentOpacity * 100) + "%";
        const splitLabel = panel.querySelector("[data-overlay-split-value]");
        if (splitLabel) splitLabel.textContent = Math.round(split * 100) + "%";
        const offsetLabel = panel.querySelector("[data-overlay-offset-value]");
        if (offsetLabel) offsetLabel.textContent = Math.round(offsetX) + "px, " + Math.round(offsetY) + "px";
      };

      for (const panel of document.querySelectorAll("[data-overlay-comparison]")) {
        const canvas = panel.querySelector("canvas[data-overlay-canvas]");
        if (!canvas) continue;
        Promise.all([
          loadImage(panel.dataset.baselineSrc),
          loadImage(panel.dataset.currentSrc),
        ]).then(([baselineImage, currentImage]) => {
          const redraw = () => drawOverlay(panel, baselineImage, currentImage);
          const updateSplitFromPointer = (event) => {
            const rect = canvas.getBoundingClientRect();
            if (!rect.width) return;
            panel.dataset.overlaySplit = String(Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)));
            redraw();
          };
          const beginAlignDrag = (event) => {
            const rect = canvas.getBoundingClientRect();
            if (!rect.width || !rect.height) return;
            panel.dataset.overlayDragClientX = String(event.clientX);
            panel.dataset.overlayDragClientY = String(event.clientY);
            panel.dataset.overlayDragStartX = String(Number(panel.dataset.overlayOffsetX || 0));
            panel.dataset.overlayDragStartY = String(Number(panel.dataset.overlayOffsetY || 0));
            panel.dataset.overlayDragScaleX = String(canvas.width / rect.width);
            panel.dataset.overlayDragScaleY = String(canvas.height / rect.height);
          };
          const updateAlignFromPointer = (event) => {
            const startClientX = Number(panel.dataset.overlayDragClientX || event.clientX);
            const startClientY = Number(panel.dataset.overlayDragClientY || event.clientY);
            const startX = Number(panel.dataset.overlayDragStartX || 0);
            const startY = Number(panel.dataset.overlayDragStartY || 0);
            const scaleX = Number(panel.dataset.overlayDragScaleX || 1);
            const scaleY = Number(panel.dataset.overlayDragScaleY || 1);
            const next = snappedOffset(
              panel,
              startX + (event.clientX - startClientX) * scaleX,
              startY + (event.clientY - startClientY) * scaleY,
              canvas.width,
              canvas.height,
            );
            panel.dataset.overlayOffsetX = String(next.x);
            panel.dataset.overlayOffsetY = String(next.y);
            redraw();
          };
          panel.querySelector("[data-overlay-mode]")?.addEventListener("change", redraw);
          panel.querySelector("[data-overlay-opacity]")?.addEventListener("input", redraw);
          panel.querySelector("[data-overlay-baseline-opacity]")?.addEventListener("input", redraw);
          panel.querySelector("[data-overlay-snap]")?.addEventListener("change", redraw);
          panel.querySelector("[data-overlay-reset]")?.addEventListener("click", () => {
            panel.dataset.overlayOffsetX = "0";
            panel.dataset.overlayOffsetY = "0";
            panel.dataset.overlaySplit = "0.5";
            redraw();
          });
          canvas.addEventListener("pointerdown", (event) => {
            const currentMode = panel.querySelector("[data-overlay-mode]")?.value;
            if (currentMode !== "split" && currentMode !== "align") return;
            canvas.setPointerCapture(event.pointerId);
            if (currentMode === "split") {
              updateSplitFromPointer(event);
            } else {
              beginAlignDrag(event);
              updateAlignFromPointer(event);
            }
          });
          canvas.addEventListener("pointermove", (event) => {
            const currentMode = panel.querySelector("[data-overlay-mode]")?.value;
            if (currentMode !== "split" && currentMode !== "align") return;
            if (!canvas.hasPointerCapture(event.pointerId)) return;
            if (currentMode === "split") updateSplitFromPointer(event);
            else updateAlignFromPointer(event);
          });
          canvas.addEventListener("pointerup", (event) => {
            if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
          });
          canvas.addEventListener("pointercancel", (event) => {
            if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
          });
          redraw();
        }).catch((error) => {
          const status = panel.querySelector("[data-overlay-status]");
          if (status) status.textContent = error.message;
        });
      }
    })();
  </script>`;
}

function normalizeHex(value) {
  const raw = String(value || "").trim().replace(/^0x/i, "").replace(/^#/, "").toUpperCase();
  if (/^[0-9A-F]{8}$/.test(raw)) return `#${raw.slice(2)}`;
  if (/^[0-9A-F]{6}$/.test(raw)) return `#${raw}`;
  return null;
}

function readTextIfExists(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
}

function walk(dir, predicate, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "node_modules" || entry.name.startsWith(".")) continue;
      walk(full, predicate, acc);
    } else if (predicate(full)) {
      acc.push(full);
    }
  }
  return acc;
}

function reactSourceLabel() {
  const relative = path.relative(repoRoot, reactRepoRoot);
  return relative && !relative.startsWith("..") ? relative : reactRepoRoot;
}

function assertReactSourceReady(reason) {
  const requiredFiles = [
    path.join(webRoot, "package.json"),
    path.join(srcRoot, "index.css"),
    path.join(srcRoot, "components"),
  ];
  const missing = requiredFiles.find((item) => !fs.existsSync(item));
  if (!missing) return;

  const defaultHint = path.resolve(reactRepoRoot) === path.resolve(defaultReactRepoRoot)
    ? "Run `git submodule update --init third_party/slock-source` from the Android repo, or pass --react-repo-dir /path/to/slock."
    : "Pass --react-repo-dir /path/to/slock or set SLOCK_REACT_REPO_DIR to a Slock web source checkout.";
  throw new Error(
    `React source repo is not ready for ${reason}: missing ${path.relative(reactRepoRoot, missing)} under ${reactSourceLabel()}. ${defaultHint}`,
  );
}

function assertReactProviderHarnessReady() {
  assertReactSourceReady("react capture");
  const requiredFiles = [
    path.join(webRoot, "playwright.visual-testing.config.ts"),
    path.join(srcRoot, "visual-testing", "VisualTestingCases.tsx"),
  ];
  const missing = requiredFiles.find((item) => !fs.existsSync(item));
  if (!missing) return;
  throw new Error(
    `React visual provider harness is missing from ${reactSourceLabel()}: ${path.relative(reactRepoRoot, missing)}. ` +
      "The visual-testing CLI will not fall back to another web checkout; update third_party/slock-source to a revision with the provider harness or pass --react-repo-dir explicitly for a one-off run.",
  );
}

function extractThemeTokens(css) {
  const themeMatch = css.match(/@theme\s*\{([\s\S]*?)\n\}/);
  if (!themeMatch) return {};
  const tokens = {};
  for (const line of themeMatch[1].split(/\n/)) {
    const match = line.match(/--([a-zA-Z0-9-]+):\s*([^;]+);/);
    if (match) tokens[match[1]] = match[2].trim();
  }
  return tokens;
}

function extractReactComponent(file) {
  const source = fs.readFileSync(file, "utf8");
  const names = new Set();
  const patterns = [
    /export\s+default\s+function\s+([A-Z][A-Za-z0-9_]*)/g,
    /export\s+function\s+([A-Z][A-Za-z0-9_]*)/g,
    /export\s+const\s+([A-Z][A-Za-z0-9_]*)\s*=/g,
    /const\s+([A-Z][A-Za-z0-9_]*)\s*=\s*(?:memo|forwardRef|\()/g,
    /function\s+([A-Z][A-Za-z0-9_]*)\s*\(/g,
  ];
  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) names.add(match[1]);
  }
  const testIds = [...source.matchAll(/data-testid=["'`]([^"'`]+)["'`]/g)].map((match) => match[1]);
  const visualClassSamples = [...source.matchAll(/className=["'`]([^"'`]+)["'`]/g)]
    .map((match) => match[1])
    .filter((value) => value.includes("brutal") || value.includes("border") || value.includes("font") || value.includes("shadow"))
    .slice(0, 12);
  if (names.size === 0 && testIds.length === 0) return null;
  return {
    path: path.relative(reactRepoRoot, file),
    components: [...names].sort(),
    testIds: [...new Set(testIds)].sort(),
    visualClassSamples: [...new Set(visualClassSamples)].sort(),
  };
}

function normalizeCase(item, knownPaths) {
  const captureType = normalizeCaptureType(item.captureType, item.id);
  const category = item.category || caseCategory(item);
  return {
    ...item,
    captureType,
    captureGroup: captureTypeLabel(captureType),
    category,
    surface: item.surface || caseSurface(item) || "unknown",
    fixture: item.fixture || `fixtures/${item.id}.json`,
    viewport: item.viewport || { width: 390, height: 844, density: 3 },
    theme: item.theme || "light",
    locale: item.locale || "zh-CN",
    tolerance: item.tolerance || { pixelRatio: 0.02, layoutDp: 1, ignoreAntialiasing: true },
    capture: {
      ...(item.capture || {}),
      selector: item.selector || item.capture?.selector || `[data-visual-case='${item.id}']`,
      crop: item.capture?.crop || "element",
    },
    variants: normalizeVariants(item),
    reactSourceExists: item.reactPathHint ? knownPaths.has(item.reactPathHint) : false,
  };
}

function normalizeVariants(item) {
  if (Array.isArray(item.variants) && item.variants.length > 0) {
    return item.variants.map((variant) => ({
      id: variant.id,
      name: variant.name || variant.id,
      fixture: variant.fixture || item.fixture || `fixtures/${item.id}.json`,
      props: variant.props || {},
      interactions: variant.interactions || [],
    }));
  }
  return [
    {
      id: "default",
      name: "Default",
      fixture: item.fixture || `fixtures/${item.id}.json`,
      props: {},
      interactions: [],
    },
  ];
}

function scanReact() {
  assertReactSourceReady("scan");
  const componentFiles = walk(path.join(srcRoot, "components"), (file) => /\.(tsx|ts)$/.test(file));
  const inventory = componentFiles.map(extractReactComponent).filter(Boolean);
  const cssTokens = extractThemeTokens(fs.readFileSync(path.join(srcRoot, "index.css"), "utf8"));
  const sharedTokens = fs.existsSync(sharedTokensPath) ? readJson(sharedTokensPath) : {};
  const tokens = { ...cssTokens, shared: sharedTokens };
  const knownPaths = new Set(inventory.map((item) => item.path));
  const sharedManifest = fs.existsSync(sharedCasesPath) ? readJson(sharedCasesPath) : null;
  const rawCases = sharedManifest?.cases?.length ? sharedManifest.cases : defaultCases;
  const cases = rawCases.map((item) => normalizeCase(item, knownPaths));
  const componentMatrix = normalizeComponentMatrix(sharedManifest, cases);

  ensureDir(artifactRoot);
  writeJson(inventoryPath, { generatedAt: new Date().toISOString(), tokens, inventory });
  writeJson(defaultManifestPath, {
    version: sharedManifest?.version || 1,
    name: sharedManifest?.name || "Slock Visual Testing",
    cases,
    componentMatrix,
  });
  writeJson(providerSpecPath, {
    version: 1,
    name: "Visual Testing Provider Spec",
    env: [
      "SLOCK_VISUAL_PROVIDER",
      "SLOCK_VISUAL_CASE_ID",
      "SLOCK_VISUAL_VARIANT_ID",
      "SLOCK_VISUAL_CASE_JSON",
      "SLOCK_VISUAL_RESULT_DIR",
    ],
    output: {
      image: "visual-testing-results/<provider>/<case-id>.<png|svg|jpg|jpeg|webp>",
      metadata: "visual-testing-results/<provider>/<case-id>.metadata.json",
    },
    metadataRequiredFields: ["provider", "caseId", "image", "viewport", "capturedAt"],
    metadataOptionalFields: {
      typography: "Array or object with text/style probes such as selector, text, fontFamily, fontSize, fontWeight, lineHeight, letterSpacing, color.",
      styleTokens: "Array or object with relevant design-token/computed-style probes such as backgroundColor, borderColor, shadow, radius, spacing.",
      computedStyles: "Array or object for provider-specific computed CSS/KMP style snapshots. Prefer stable selector/component labels.",
    },
  });
  console.log(`scan: ${inventory.length} component files, ${Object.keys(tokens).length} tokens, ${cases.length} cases, ${componentMatrix.length} matrix rows`);
}

function normalizeComponentMatrix(sharedManifest, cases) {
  const rows = new Map();
  const skippedCases = new Map(cases.filter(isSkippedCase).map((visualCase) => [visualCase.id, visualCase]));
  for (const visualCase of cases) {
    rows.set(visualCase.id, normalizeMatrixRow({
      id: visualCase.id,
      title: visualCase.title || visualCase.id,
      captureType: visualCase.captureType,
      captureGroup: visualCase.captureGroup,
      category: visualCase.category || caseCategory(visualCase),
      surface: visualCase.surface || "unknown",
      group: visualCase.group || visualCase.category || visualCase.surface || "unknown",
      status: isSkippedCase(visualCase) ? "skipped" : visualCase.baselineStatus || "active",
      providers: visualCase.providerMatrix || {},
      notes: skipReason(visualCase),
    }));
  }
  for (const row of sharedManifest?.componentMatrix || []) {
    const base = rows.get(row.id) || {};
    const skippedCase = skippedCases.get(row.id);
    rows.set(row.id, normalizeMatrixRow({
      ...base,
      ...row,
      status: skippedCase ? "skipped" : row.status || base.status,
      notes: skippedCase ? skipReason(skippedCase) : row.notes || base.notes,
      providers: mergeProviderMatrix(base.providers, row.providers),
    }));
  }
  return [...rows.values()].sort((a, b) => {
    const groupCompare = String(a.group || "").localeCompare(String(b.group || ""));
    return groupCompare || String(a.id).localeCompare(String(b.id));
  });
}

function normalizeMatrixRow(row) {
  const captureType = normalizeCaptureType(row.captureType, row.id);
  return {
    id: row.id,
    title: row.title || row.id,
    captureType,
    captureGroup: row.captureGroup || captureTypeLabel(captureType),
    category: row.category || caseCategory(row),
    surface: row.surface || "unknown",
    group: row.group || row.category || row.surface || "unknown",
    status: row.status || "active",
    providers: normalizeProviderMatrix(row.providers),
    notes: row.notes || "",
  };
}

function skipReason(visualCase) {
  if (!isSkippedCase(visualCase)) return visualCase.notes || "";
  if (typeof visualCase.skip === "object" && visualCase.skip.reason) return visualCase.skip.reason;
  return visualCase.notes || "Skipped from visual similarity accounting.";
}

function normalizeProviderMatrixEntry(value) {
  if (typeof value === "string") return { status: value, components: [], path: "", fixture: "", reason: "" };
  return {
    ...value,
    status: value.status || "pending",
    components: Array.isArray(value.components) ? value.components : [],
    path: value.path || "",
    fixture: value.fixture || "",
    reason: value.reason || "",
  };
}

function normalizeProviderMatrix(providers) {
  const normalized = {};
  const entries = Array.isArray(providers)
    ? providers.map((entry) => [entry.id || entry.provider || entry.name, entry])
    : Object.entries(providers || {});
  for (const [provider, value] of entries) {
    if (!provider) continue;
    normalized[provider] = normalizeProviderMatrixEntry(value);
  }
  return normalized;
}

function mergeProviderMatrix(baseProviders, nextProviders) {
  return {
    ...normalizeProviderMatrix(baseProviders),
    ...normalizeProviderMatrix(nextProviders),
  };
}

function resolveManifestPath(manifestFlag) {
  if (!manifestFlag) return defaultManifestPath;
  if (manifestFlag === "shared" || manifestFlag === "package") return sharedCasesPath;
  return path.resolve(manifestFlag);
}

function loadManifest(flags, { createIfMissing = true } = {}) {
  const manifestPath = resolveManifestPath(flags.manifest);
  if (!fs.existsSync(manifestPath) && createIfMissing) {
    scanReact();
  }
  return readJson(manifestPath);
}

function xmlEscape(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function runExternalProvider(provider, command, visualCase) {
  const env = {
    ...process.env,
    SLOCK_VISUAL_PROVIDER: provider,
    SLOCK_VISUAL_CASE_ID: visualCase.id,
    SLOCK_VISUAL_CASE_JSON: JSON.stringify(visualCase),
    SLOCK_VISUAL_RESULT_DIR: path.join(resultRoot, provider),
  };
  ensureDir(env.SLOCK_VISUAL_RESULT_DIR);
  const result = spawnSync(command, { shell: true, stdio: "inherit", cwd: repoRoot, env });
  if (result.status !== 0) {
    throw new Error(`provider ${provider} failed for ${visualCase.id} with status ${result.status}`);
  }
}

function runReactProvider(cases, flags) {
  assertReactProviderHarnessReady();
  const env = {
    ...process.env,
    SLOCK_VISUAL_PROVIDER: "react",
    SLOCK_VISUAL_CASE_IDS: cases.map((item) => item.id).join(","),
    SLOCK_VISUAL_RESULT_ROOT: resultRoot,
    PLAYWRIGHT_WEB_PORT: String(flags.webPort || process.env.PLAYWRIGHT_WEB_PORT || "4173"),
  };
  const result = spawnSync(
    "pnpm",
    [
      "--filter",
      "@slock-ai/web",
      "exec",
      "playwright",
      "test",
      "--config",
      "playwright.visual-testing.config.ts",
    ],
    { stdio: "inherit", cwd: reactRepoRoot, env },
  );
  if (result.status !== 0) {
    throw new Error(`react provider failed with status ${result.status}`);
  }
}

function selectedCases(manifest, flags) {
  const only = flags.case ? new Set(String(flags.case).split(",").map((item) => item.trim()).filter(Boolean)) : null;
  const filters = String(flags.category || flags.filter || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  const includePending = Boolean(flags["include-pending"]);
  const includeSkipped = Boolean(flags["include-skipped"]);
  const allCases = manifest.cases || [];
  let cases = only
    ? allCases.filter((item) => only.has(item.id))
    : allCases.filter((item) => includePending || item.baselineStatus !== "pending");
  cases = cases.filter((item) => includeSkipped || !isSkippedCase(item));
  if (filters.length > 0) {
    cases = cases.filter((item) => filters.some((filter) => caseMatchesFilter(item, filter)));
  }
  if (only && cases.length !== only.size) {
    const found = new Set(cases.map((item) => item.id));
    const missing = [...only].filter((item) => !found.has(item));
    const skipped = allCases.filter((item) => only.has(item.id) && isSkippedCase(item)).map((item) => item.id);
    if (skipped.length > 0 && !includeSkipped) {
      throw new Error(`Skipped visual testing case(s): ${skipped.join(", ")}. Pass --include-skipped to run them explicitly.`);
    }
    throw new Error(`Unknown visual testing case(s): ${missing.join(", ")}`);
  }
  return cases;
}

function isSkippedCase(visualCase) {
  const value = visualCase?.skip;
  if (value === true) return true;
  return Boolean(value && typeof value === "object" && value.enabled !== false);
}

function hasCaseSelection(flags) {
  return Boolean(flags.case || flags.category || flags.filter);
}

function expectedFullPublishCaseCount(flags) {
  const manifest = loadManifest(flags);
  return selectedCases(manifest, {
    ...flags,
    case: undefined,
    category: undefined,
    filter: undefined,
  }).length;
}

function normalizeCaptureType(value, id = "") {
  const raw = String(value || "").trim();
  if (captureTypeValues.has(raw)) return raw;
  if (String(id || "").startsWith("screens.")) return realScreenCaptureType;
  return componentFixtureCaptureType;
}

function captureTypeLabel(value) {
  return captureTypeLabels[normalizeCaptureType(value)] || captureTypeLabels[componentFixtureCaptureType];
}

function captureTypeSegment(value) {
  return captureTypeSegments[normalizeCaptureType(value)] || captureTypeSegments[componentFixtureCaptureType];
}

function caseSurface(visualCase) {
  const id = String(visualCase.id || "");
  if (!id) return visualCase.surface || "unknown";
  const parts = id.split(".");
  if ((parts[0] === "components" || parts[0] === "screens") && parts[1]) return parts[1];
  return visualCase.surface || parts[0] || "unknown";
}

function caseCategory(visualCase) {
  if (visualCase.category) return visualCase.category;
  if (String(visualCase.id || "").includes("/")) {
    const parts = String(visualCase.id).split("/");
    return parts.slice(0, -1).join("/") || parts[0] || "uncategorized";
  }
  const surface = caseSurface(visualCase);
  if (["home", "thread", "settings", "navigation", "tasks"].includes(surface)) return `app/${surface}`;
  if (surface === "components") return "components";
  if (surface === "tokens") return "tokens";
  if (surface === "auth") return "auth";
  return surface;
}

function casePath(visualCase) {
  if (String(visualCase.id || "").includes("/")) return String(visualCase.id);
  const captureType = normalizeCaptureType(visualCase.captureType, visualCase.id);
  const captureSegment = captureTypeSegment(captureType);
  const category = caseCategory(visualCase);
  const id = String(visualCase.id || "");
  const taxonomyPrefix = `${captureSegment}.`;
  const withoutTaxonomy = id.startsWith(taxonomyPrefix) ? id.slice(taxonomyPrefix.length) : id;
  const surfacePrefix = `${caseSurface(visualCase)}.`;
  const local = surfacePrefix !== "." && withoutTaxonomy.startsWith(surfacePrefix)
    ? withoutTaxonomy.slice(surfacePrefix.length)
    : withoutTaxonomy;
  return `${captureSegment}/${category}/${local.replaceAll(".", "/")}`;
}

function caseMatchesFilter(visualCase, filter) {
  const normalized = filter.replace(/\/+$/g, "");
  const captureType = normalizeCaptureType(visualCase.captureType, visualCase.id);
  const candidates = [
    visualCase.id,
    captureType,
    captureTypeLabel(captureType),
    captureTypeSegment(captureType),
    caseCategory(visualCase),
    casePath(visualCase),
    visualCase.surface,
    visualCase.group,
  ]
    .filter(Boolean)
    .map(String);
  if (normalized.endsWith("/*")) {
    const prefix = normalized.slice(0, -1);
    return candidates.some((candidate) => candidate.startsWith(prefix));
  }
  return candidates.some((candidate) => candidate === normalized || candidate.startsWith(`${normalized}/`));
}

function capture(flags) {
  const manifest = loadManifest(flags);
  const providers = String(flags.providers || "react").split(",").map((item) => item.trim()).filter(Boolean);
  const command = flags.command ? String(flags.command) : null;
  const cases = selectedCases(manifest, flags);
  for (const provider of providers) {
    if (!command && provider === "react") {
      runReactProvider(cases, flags);
      continue;
    }
    if (command) {
      for (const visualCase of cases) {
        runExternalProvider(provider, command, visualCase);
      }
      continue;
    }
    throw new Error(`provider ${provider} requires --command; built-in demo provider output is disabled for canonical visual testing`);
  }
  console.log(`capture: ${cases.length} cases x ${providers.length} providers`);
}

function hashFile(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function findImage(provider, caseId) {
  const dir = path.join(resultRoot, provider);
  for (const ext of ["png", "svg", "jpg", "jpeg", "webp"]) {
    const candidate = path.join(dir, `${caseId}.${ext}`);
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

function readMetadata(provider, caseId) {
  const file = path.join(resultRoot, provider, `${caseId}.metadata.json`);
  return fs.existsSync(file) ? readJson(file) : null;
}

function isCanonicalProviderCapture(provider, metadata) {
  return !String(provider || "").endsWith("-demo") && (!metadata || metadata.providerType !== "builtin-demo");
}

function diff(flags) {
  const manifest = loadManifest(flags);
  const baseline = String(flags.baseline || "react");
  const current = String(flags.current || "android");
  const cases = selectedCases(manifest, flags);
  const results = [];
  const comparisonDir = path.join(resultRoot, "diff", `${baseline}__${current}`);
  for (const visualCase of cases) {
    const rawBaselineImage = findImage(baseline, visualCase.id);
    const rawCurrentImage = findImage(current, visualCase.id);
    const baselineMetadata = readMetadata(baseline, visualCase.id);
    const currentMetadata = readMetadata(current, visualCase.id);
    const baselineImage = rawBaselineImage && isCanonicalProviderCapture(baseline, baselineMetadata) ? rawBaselineImage : null;
    const currentImage = rawCurrentImage && isCanonicalProviderCapture(current, currentMetadata) ? rawCurrentImage : null;
    const captureType = normalizeCaptureType(visualCase.captureType, visualCase.id);
    const entry = {
      id: visualCase.id,
      surface: visualCase.surface,
      category: visualCase.category || caseCategory(visualCase),
      captureType,
      captureGroup: captureTypeLabel(captureType),
      baseline,
      current,
      tolerance: visualCase.tolerance,
      baselineImage: baselineImage ? path.relative(repoRoot, baselineImage) : null,
      currentImage: currentImage ? path.relative(repoRoot, currentImage) : null,
      baselineMetadata,
      currentMetadata,
      status: "missing",
    };
    const ignoredProviders = [];
    if (rawBaselineImage && !baselineImage) ignoredProviders.push(baseline);
    if (rawCurrentImage && !currentImage) ignoredProviders.push(current);
    if (ignoredProviders.length > 0) {
      entry.note = `Ignored non-real provider capture(s): ${ignoredProviders.join(", ")}`;
    }
    if (baselineImage && currentImage) {
      const baselineHash = hashFile(baselineImage);
      const currentHash = hashFile(currentImage);
      entry.baselineSha256 = baselineHash;
      entry.currentSha256 = currentHash;
      entry.rawStatus = baselineHash === currentHash ? "same" : "different";
      entry.status = entry.rawStatus;
      if (path.extname(baselineImage).toLowerCase() === ".png" && path.extname(currentImage).toLowerCase() === ".png") {
        const sideBySidePath = path.join(comparisonDir, `${visualCase.id}.side-by-side.png`);
        const metricsPath = path.join(comparisonDir, `${visualCase.id}.metrics.json`);
        runImageDiff(baselineImage, currentImage, sideBySidePath, metricsPath, visualCase, baseline, current);
        const metrics = readJson(metricsPath);
        entry.sideBySideImage = path.relative(repoRoot, sideBySidePath);
        entry.metrics = metrics;
        entry.status = comparisonStatusFromMetrics(entry.rawStatus, metrics);
        if (entry.status !== entry.rawStatus) entry.acceptance = comparisonAcceptance(entry.status, metrics);
        entry.note = "Side-by-side and site raw captures preserve provider image sizes. Metrics use an in-memory comparison canvas: rgbSimilarity rescales for coarse color similarity, while pixelPerfectSimilarity pads the original images without cropping.";
      } else {
        entry.note = "File-level comparison only; PNG metrics require PNG inputs.";
      }
    }
    results.push(entry);
  }
  ensureDir(path.join(resultRoot, "diff"));
  writeJson(path.join(resultRoot, "diff", `${baseline}__${current}.json`), {
    generatedAt: new Date().toISOString(),
    baseline,
    current,
    results,
    summary: summarize(results),
  });
  console.log(`diff: ${baseline} vs ${current}, ${results.length} cases`);
}

function comparisonStatusFromMetrics(rawStatus, metrics) {
  if (rawStatus !== "different") return rawStatus;
  const pixel = Number(metrics?.pixelPerfectSimilarity);
  if (!Number.isFinite(pixel)) return rawStatus;
  if (pixel > pixelPerfectPassThreshold) return "pass";
  if (pixel > pixelPerfectBasicPassThreshold) return "basic-pass";
  return rawStatus;
}

function comparisonAcceptance(status, metrics) {
  const pixel = Number(metrics?.pixelPerfectSimilarity || 0) * 100;
  if (status === "pass") return `pass: pixel perfect ${pixel.toFixed(2)}% > ${(pixelPerfectPassThreshold * 100).toFixed(0)}%`;
  if (status === "basic-pass") return `basic pass: pixel perfect ${pixel.toFixed(2)}% > ${(pixelPerfectBasicPassThreshold * 100).toFixed(0)}%`;
  return "";
}

function runImageDiff(baselineImage, currentImage, sideBySidePath, metricsPath, visualCase, baseline, current) {
  ensureDir(path.dirname(sideBySidePath));
  const result = spawnSync(
    "node",
    [
      sharpImageDiffScriptPath,
      baselineImage,
      currentImage,
      sideBySidePath,
      metricsPath,
      visualCase.id,
      visualCase.title || visualCase.id,
      providerLabel(baseline),
      providerLabel(current),
    ],
    { stdio: "inherit", cwd: repoRoot },
  );
  if (result.status !== 0) {
    throw new Error(`visual image diff failed for ${path.basename(baselineImage)} with status ${result.status}`);
  }
}

function providerLabel(provider) {
  if (provider === "android" || provider === "android-connected" || provider === "kmp-shared") return "Android";
  if (provider === "harmony" || provider === "ohos-shared") return "Harmony";
  if (provider === "react") return "React";
  return provider;
}

function summarize(results) {
  return results.reduce(
    (acc, item) => {
      acc.total += 1;
      acc[item.status] = (acc[item.status] || 0) + 1;
      return acc;
    },
    { total: 0, same: 0, pass: 0, "basic-pass": 0, different: 0, missing: 0 },
  );
}

function report(flags) {
  const baseline = String(flags.baseline || "react");
  const current = String(flags.current || "android");
  const comparison = `${baseline}__${current}`;
  const diffPath = path.join(resultRoot, "diff", `${baseline}__${current}.json`);
  if (!fs.existsSync(diffPath)) diff(flags);
  if (shouldRunAnalysis(flags) && (flags.force || flags.analyze || flags.analysis || !fs.existsSync(analysisPath(comparison)))) analyze(flags);
  const diffData = withAnalysis(fs.existsSync(diffPath) ? readJson(diffPath) : { results: [], summary: {} }, baseline, current);
  const manifest = loadManifest(flags);
  const componentMatrix = enrichComponentMatrix(manifest.componentMatrix || normalizeComponentMatrix(null, manifest.cases || []), diffData, baseline, current);
  const componentMatrixSummary = summarizeComponentMatrix(componentMatrix);
  const html = visualDetailHtml(diffData, {
    generatedAt: diffData.generatedAt || new Date().toISOString(),
    runId: "local",
    commit: "local",
    commitSubject: "",
    baseline,
    current,
    comparison,
    summary: diffData.summary || {},
    componentMatrixSummary,
    componentMatrix,
  }, "", "./");
  ensureDir(resultRoot);
  fs.writeFileSync(path.join(resultRoot, "report.html"), html);
  console.log(`report: ${path.relative(process.cwd(), path.join(resultRoot, "report.html"))}`);
}

function analyze(flags) {
  const baseline = String(flags.baseline || "react");
  const current = String(flags.current || "android");
  const comparison = `${baseline}__${current}`;
  const diffPath = path.join(resultRoot, "diff", `${comparison}.json`);
  if (!fs.existsSync(diffPath)) diff(flags);
  const diffData = readJson(diffPath);
  const existing = readAnalysis(comparison);
  const entriesById = new Map((existing.cases || []).map((item) => [item.id, item]));
  const cases = [];
  for (const item of diffData.results || []) {
    const sideBySidePath = item.sideBySideImage ? path.join(repoRoot, item.sideBySideImage) : null;
    const imageHash = sideBySidePath && fs.existsSync(sideBySidePath) ? hashFile(sideBySidePath) : null;
    const cached = entriesById.get(item.id);
    if (cached?.imageHash === imageHash && cached?.status === "ready" && !flags.force) {
      cases.push(cached);
      continue;
    }
    cases.push(analyzeCaseWithCodex(item, { baseline, current, comparison, imageHash, sideBySidePath, flags }));
  }
  const output = {
    version: 1,
    generatedAt: new Date().toISOString(),
    baseline,
    current,
    comparison,
    summary: summarizeAnalysis(cases),
    cases,
  };
  writeJson(analysisPath(comparison), output);
  console.log(`analyze: ${comparison}, ${cases.length} cases, ${output.summary.ready} ready, ${output.summary.pending} pending`);
  return output;
}

function analyzeCaseWithCodex(item, context) {
  const base = {
    id: item.id,
    status: "pending",
    imageHash: context.imageHash,
    generatedAt: new Date().toISOString(),
    severity: "unknown",
    summary: [],
    likelyCauses: [],
    suggestedOwner: "",
    suggestedFixes: [],
    error: null,
  };
  if (!context.sideBySidePath || !fs.existsSync(context.sideBySidePath)) {
    return { ...base, error: "missing side-by-side image" };
  }
  if (context.flags["dry-run"] || context.flags["skip-model"]) {
    return { ...base, error: "analysis skipped by flag" };
  }
  const codexPath = findExecutable("codex");
  if (!codexPath) return { ...base, error: "codex CLI not found" };
  const outputFile = path.join(os.tmpdir(), `slock-visual-analysis-${process.pid}-${anchorId(item.id)}.json`);
  const schemaFile = path.join(os.tmpdir(), `slock-visual-analysis-schema-${process.pid}.json`);
  fs.writeFileSync(schemaFile, `${JSON.stringify(analysisJsonSchema(), null, 2)}\n`);
  const prompt = visualAnalysisPrompt(item, context);
  const args = [
    "exec",
    "--ephemeral",
    "--sandbox",
    "read-only",
    "-C",
    repoRoot,
    "--image",
    context.sideBySidePath,
    "--output-schema",
    schemaFile,
    "-o",
    outputFile,
    prompt,
  ];
  if (context.flags.model) args.splice(1, 0, "--model", String(context.flags.model));
  try {
    const result = spawnSync(codexPath, args, { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: Number(context.flags.timeout || 180000) });
    if (result.status !== 0 || !fs.existsSync(outputFile)) {
      return {
        ...base,
        error: `codex analysis failed${result.status === null ? " (timeout)" : ` status ${result.status}`}`,
        stderr: trimForJson(result.stderr || result.stdout || "", 1200),
      };
    }
    const parsed = JSON.parse(fs.readFileSync(outputFile, "utf8"));
    return normalizeAnalysisCase({ ...base, ...parsed, id: item.id, status: "ready", imageHash: context.imageHash });
  } catch (error) {
    return { ...base, error: `invalid codex JSON: ${error.message}`, raw: trimForJson(fs.readFileSync(outputFile, "utf8"), 1200) };
  } finally {
    fs.rmSync(outputFile, { force: true });
    fs.rmSync(schemaFile, { force: true });
  }
}

function visualAnalysisPrompt(item, context) {
  const metadata = {
    id: item.id,
    status: item.status,
    baseline: context.baseline,
    current: context.current,
    baselineRawSize: rawSizeText(item.baselineMetadata),
    currentRawSize: rawSizeText(item.currentMetadata),
    rgbSimilarity: item.metrics?.rgbSimilarity ?? null,
    pixelPerfectSimilarity: item.metrics?.pixelPerfectSimilarity ?? null,
    pixelMismatchRatio: item.metrics?.pixelMismatchRatio ?? null,
    crop: {
      baseline: item.baselineMetadata?.crop || null,
      current: item.currentMetadata?.crop || null,
    },
  };
  return `Analyze this Slock visual-testing side-by-side image. The left side is ${providerLabel(context.baseline)} and the right side is ${providerLabel(context.current)}. Use the image plus metadata to produce concise, actionable UI differences. Focus on concrete visual differences only: typography weight/size, spacing, alignment, color, shape, missing elements, clipping/cropping, state indicators, and component bounds. Return JSON only matching the schema.\n\nMetadata:\n${JSON.stringify(metadata, null, 2)}`;
}

function analysisJsonSchema() {
  return {
    type: "object",
    additionalProperties: false,
    required: ["severity", "summary", "likelyCauses", "suggestedOwner", "suggestedFixes"],
    properties: {
      severity: { type: "string", enum: ["low", "medium", "high", "unknown"] },
      summary: { type: "array", minItems: 1, maxItems: 5, items: { type: "string" } },
      likelyCauses: { type: "array", maxItems: 5, items: { type: "string" } },
      suggestedOwner: { type: "string" },
      suggestedFixes: { type: "array", maxItems: 5, items: { type: "string" } },
    },
  };
}

function normalizeAnalysisCase(value) {
  return {
    id: String(value.id || ""),
    status: value.status === "ready" ? "ready" : "pending",
    imageHash: value.imageHash || null,
    generatedAt: value.generatedAt || new Date().toISOString(),
    severity: ["low", "medium", "high", "unknown"].includes(value.severity) ? value.severity : "unknown",
    summary: asStringArray(value.summary).slice(0, 5),
    likelyCauses: asStringArray(value.likelyCauses).slice(0, 5),
    suggestedOwner: String(value.suggestedOwner || ""),
    suggestedFixes: asStringArray(value.suggestedFixes).slice(0, 5),
    error: value.error || null,
  };
}

function asStringArray(value) {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : [];
}

function summarizeAnalysis(cases) {
  return cases.reduce(
    (acc, item) => {
      acc.total += 1;
      if (item.status === "ready") acc.ready += 1;
      else acc.pending += 1;
      return acc;
    },
    { total: 0, ready: 0, pending: 0 },
  );
}

function withAnalysis(diffData, baseline, current) {
  const comparison = `${baseline}__${current}`;
  const analysis = readAnalysis(comparison);
  const byId = new Map((analysis.cases || []).map((item) => [item.id, item]));
  const results = (diffData.results || []).map((item) => {
    const currentHash = currentSideBySideHash(item);
    const cached = byId.get(item.id);
    const isValid = cached?.status === "ready" && cached.imageHash && cached.imageHash === currentHash;
    return {
      ...item,
      analysis: isValid
        ? cached
        : pendingAnalysisForItem(item, currentHash, cached ? "analysis stale; rerun analyze" : "analysis pending"),
    };
  });
  const cases = results.map((item) => item.analysis).filter(Boolean);
  return {
    ...diffData,
    analysis: {
      ...analysis,
      summary: summarizeAnalysis(cases),
      cases,
    },
    results,
  };
}

function readAnalysis(comparison) {
  const file = analysisPath(comparison);
  return fs.existsSync(file) ? readJson(file) : { version: 1, cases: [], summary: { total: 0, ready: 0, pending: 0 } };
}

function analysisPath(comparison) {
  return path.join(analysisRoot, `${comparison}.json`);
}

function currentSideBySideHash(item) {
  const sideBySidePath = item.sideBySideImage ? path.join(repoRoot, item.sideBySideImage) : null;
  return sideBySidePath && fs.existsSync(sideBySidePath) ? hashFile(sideBySidePath) : null;
}

function pendingAnalysisForItem(item, imageHash, error) {
  return {
    id: item.id,
    status: "pending",
    imageHash,
    generatedAt: new Date().toISOString(),
    severity: "unknown",
    summary: [],
    likelyCauses: [],
    suggestedOwner: "",
    suggestedFixes: [],
    error: item.sideBySideImage ? error : "missing side-by-side image",
  };
}

function findExecutable(name) {
  const result = spawnSync("command", ["-v", name], { shell: true, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
  return result.status === 0 ? result.stdout.trim().split(/\n/)[0] : "";
}

function trimForJson(value, maxLength) {
  const text = String(value || "");
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}…`;
}

function copyFileIntoSite(source, destination) {
  if (!fs.existsSync(source)) return false;
  ensureDir(path.dirname(destination));
  fs.copyFileSync(source, destination);
  return true;
}

function copyDirIntoSite(source, destination) {
  if (!fs.existsSync(source)) return false;
  ensureDir(destination);
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const sourcePath = path.join(source, entry.name);
    const destinationPath = path.join(destination, entry.name);
    if (entry.isDirectory()) {
      copyDirIntoSite(sourcePath, destinationPath);
    } else if (entry.isFile()) {
      copyFileIntoSite(sourcePath, destinationPath);
    }
  }
  return true;
}

function site(flags) {
  const baseline = String(flags.baseline || "react");
  const current = String(flags.current || "android");
  const comparison = `${baseline}__${current}`;
  const manifest = loadManifest(flags);
  const runId = sourceRunId(flags);
  const diffPath = path.join(resultRoot, "diff", `${comparison}.json`);
  if (!fs.existsSync(diffPath)) diff(flags);
  if (shouldRunAnalysis(flags)) analyze(flags);
  report(flags);

  const targetRoot = path.resolve(flags.siteDir || flags["site-dir"] || siteRoot);
  const latestDir = path.join(targetRoot, "latest");
  const runDir = path.join(targetRoot, "runs", runId);
  fs.rmSync(latestDir, { recursive: true, force: true });
  fs.rmSync(runDir, { recursive: true, force: true });
  ensureDir(latestDir);
  ensureDir(runDir);

  const diffData = withAnalysis(readJson(diffPath), baseline, current);
  const componentMatrix = enrichComponentMatrix(manifest.componentMatrix || normalizeComponentMatrix(null, manifest.cases || []), diffData, baseline, current);
  const componentMatrixSummary = summarizeComponentMatrix(componentMatrix);
  const metadata = {
    generatedAt: new Date().toISOString(),
    runId,
    commit: runId,
    commitSubject: sourceCommitSubject(flags),
    baseline,
    current,
    comparison,
    summary: diffData.summary || {},
    analysisSummary: diffData.analysis?.summary || {},
    componentMatrixSummary,
    componentMatrix,
    report: "latest/report.html",
  };
  writeRunContent(latestDir, diffData, metadata, comparison, "../");
  writeRunContent(runDir, diffData, { ...metadata, report: `runs/${runId}/report.html` }, comparison, "../../");
  const runs = [runSummary(metadata, `runs/${runId}/`)];
  writeJson(path.join(targetRoot, "metadata.json"), metadata);
  writeJson(path.join(targetRoot, "index.json"), siteIndexJson(metadata, runs));
  fs.writeFileSync(path.join(targetRoot, "llms.txt"), siteLlmsText(metadata, runs));
  fs.writeFileSync(path.join(targetRoot, "index.html"), siteHomeHtml(metadata, runs));

  console.log(`site: ${path.relative(process.cwd(), targetRoot)}`);
  return targetRoot;
}

function shouldRunAnalysis(flags) {
  return !flags["skip-analysis"] && !flags["no-analysis"] && !flags["dry-run"];
}

function writeRunContent(targetDir, diffData, metadata, comparison, homeHref) {
  copyFileIntoSite(path.join(resultRoot, "diff", `${comparison}.json`), path.join(targetDir, "diff", `${comparison}.json`));
  copyFileIntoSite(analysisPath(comparison), path.join(targetDir, "analysis", `${comparison}.json`));
  copyCurrentResultFiles(diffData, targetDir, comparison);
  writeJson(path.join(targetDir, "component-matrix.json"), {
    generatedAt: metadata.generatedAt,
    runId: metadata.runId,
    baseline: metadata.baseline,
    current: metadata.current,
    summary: metadata.componentMatrixSummary || {},
    rows: metadata.componentMatrix || [],
  });
  writeJson(path.join(targetDir, "metadata.json"), metadata);
  if (diffData.analysis?.summary) {
    writeJson(path.join(targetDir, "analysis-summary.json"), diffData.analysis.summary);
  }
  const html = siteIndexHtml(diffData, metadata, "", homeHref);
  fs.writeFileSync(path.join(targetDir, "index.html"), html);
  fs.writeFileSync(path.join(targetDir, "report.html"), html);
}

function normalizeRunId(value) {
  return String(value || "local").trim().replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "") || "local";
}

function gitOutput(cwd, args) {
  const result = spawnSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
  return result.status === 0 ? result.stdout.trim() : "";
}

function sourceRepoRoot(flags) {
  const explicit = flags.sourceRepoDir || flags["source-repo-dir"] || process.env.SLOCK_VISUAL_SOURCE_REPO;
  return explicit ? path.resolve(explicit) : repoRoot;
}

function sourceRunId(flags) {
  return normalizeRunId(flags.runId || flags["run-id"] || gitOutput(sourceRepoRoot(flags), ["rev-parse", "--short", "HEAD"]) || "local");
}

function sourceCommitSubject(flags) {
  return String(flags.commitSubject || flags["commit-subject"] || gitOutput(sourceRepoRoot(flags), ["log", "-1", "--pretty=%s"]) || "");
}

function runSummary(metadata, hrefPrefix) {
  return {
    runId: metadata.runId,
    commit: metadata.commit,
    commitSubject: metadata.commitSubject,
    generatedAt: metadata.generatedAt,
    baseline: metadata.baseline,
    current: metadata.current,
    summary: metadata.summary,
    analysisSummary: metadata.analysisSummary || {},
    componentMatrixSummary: metadata.componentMatrixSummary,
    href: hrefPrefix,
    report: `${hrefPrefix}report.html`,
    componentMatrix: `${hrefPrefix}component-matrix.json`,
    llms: `${hrefPrefix}metadata.json`,
  };
}

function enrichComponentMatrix(matrix, diffData, baseline, current) {
  const resultById = new Map((diffData.results || []).map((item) => [item.id, item]));
  return (matrix || []).map((row) => {
    const result = resultById.get(row.id);
    const captureType = normalizeCaptureType(row.captureType, row.id);
    return {
      ...row,
      captureType,
      captureGroup: row.captureGroup || captureTypeLabel(captureType),
      category: row.category || caseCategory(row),
      comparison: result
        ? {
            baseline,
            current,
            status: result.status,
            rawStatus: result.rawStatus || result.status,
            acceptance: result.acceptance || "",
            rgbSimilarity: result.metrics?.rgbSimilarity ?? null,
            pixelPerfectSimilarity: result.metrics?.pixelPerfectSimilarity ?? null,
            sideBySideImage: result.sideBySideImage || null,
          }
        : null,
    };
  });
}

function summarizeComponentMatrix(matrix) {
  const summary = {
    total: 0,
    active: 0,
    pending: 0,
    skipped: 0,
    captureTypes: {
      [componentFixtureCaptureType]: { total: 0, active: 0, pending: 0, skipped: 0 },
      [realScreenCaptureType]: { total: 0, active: 0, pending: 0, skipped: 0 },
    },
    providers: {},
  };
  for (const row of matrix || []) {
    const captureType = normalizeCaptureType(row.captureType, row.id);
    summary.total += 1;
    if (isActiveCoverageStatus(row.status)) summary.active += 1;
    else if (row.status === "skipped") summary.skipped += 1;
    else summary.pending += 1;
    if (!summary.captureTypes[captureType]) summary.captureTypes[captureType] = { total: 0, active: 0, pending: 0, skipped: 0 };
    summary.captureTypes[captureType].total += 1;
    if (isActiveCoverageStatus(row.status)) summary.captureTypes[captureType].active += 1;
    else if (row.status === "skipped") summary.captureTypes[captureType].skipped += 1;
    else summary.captureTypes[captureType].pending += 1;
    for (const [provider, entry] of Object.entries(row.providers || {})) {
      if (!summary.providers[provider]) summary.providers[provider] = { active: 0, pending: 0, unsupported: 0, total: 0 };
      summary.providers[provider].total += 1;
      if (isActiveCoverageStatus(entry.status)) summary.providers[provider].active += 1;
      else if (entry.status === "unsupported") summary.providers[provider].unsupported += 1;
      else summary.providers[provider].pending += 1;
    }
  }
  return summary;
}

function isActiveCoverageStatus(status) {
  return ["active", "baseline", "ready"].includes(String(status || ""));
}

function copyCurrentResultFiles(diffData, latestDir, comparison) {
  for (const item of diffData.results || []) {
    for (const relativePath of [item.baselineImage, item.currentImage, item.sideBySideImage]) {
      copyRelativeResultFile(relativePath, latestDir);
    }
    const metricsPath = path.join(resultRoot, "diff", comparison, `${item.id}.metrics.json`);
    if (fs.existsSync(metricsPath)) {
      copyFileIntoSite(metricsPath, path.join(latestDir, "diff", comparison, `${item.id}.metrics.json`));
    }
    for (const metadata of [item.baselineMetadata, item.currentMetadata]) {
      if (metadata?.image) {
        const parsed = path.parse(metadata.image);
        copyRelativeResultFile(path.join(parsed.dir, `${parsed.name}.metadata.json`), latestDir);
      }
    }
    copyFileIntoSite(path.join(resultRoot, item.baseline, `${item.id}.metadata.json`), path.join(latestDir, item.baseline, `${item.id}.metadata.json`));
    copyFileIntoSite(path.join(resultRoot, item.current, `${item.id}.metadata.json`), path.join(latestDir, item.current, `${item.id}.metadata.json`));
  }
}

function copyRelativeResultFile(relativePath, latestDir) {
  if (!relativePath) return;
  const absolutePath = path.join(repoRoot, relativePath);
  if (!absolutePath.startsWith(resultRoot)) return;
  copyFileIntoSite(absolutePath, path.join(latestDir, path.relative(resultRoot, absolutePath)));
}

function siteIndexHtml(diffData, metadata, latestPrefix, homeHref = "./") {
  return visualDetailHtml(diffData, metadata, latestPrefix, homeHref);
}

function visualDetailHtml(diffData, metadata, latestPrefix, homeHref = "./") {
  const matrixHref = `${latestPrefix}component-matrix.json`;
  const diffJsonHref = `${latestPrefix}diff/${metadata.comparison}.json`;
  const metadataHref = `${latestPrefix}metadata.json`;
  const tree = categoryTreeHtml(diffData.results || [], latestPrefix);
  const groups = groupedCaseCards(diffData.results || [], latestPrefix);
  const analysisSummary = diffData.analysis?.summary || metadata.analysisSummary || {};
  const capturePills = captureTypeSummaryPills(metadata.componentMatrixSummary);
  const rows = groups.map((group) => {
    const cards = group.items.map((item) => caseCardHtml(item, latestPrefix)).join("\n");
    return `<section class="caseGroup" id="group-${xmlEscape(anchorId(group.category))}">
      <h2>${xmlEscape(group.category)}</h2>
      <div class="grid">${cards}</div>
    </section>`;
  }).join("\n");
  const matrixRows = componentMatrixHtml(metadata.componentMatrix || []);
  const matrixSection = matrixRows
    ? `<section class="machineData">
      <details>
        <summary>Visual coverage matrix</summary>
        <div class="matrix">
          <table>
            <thead><tr><th>Type</th><th>Group</th><th>Case</th><th>Status</th><th>Providers</th><th>${xmlEscape(metadata.baseline)} vs ${xmlEscape(metadata.current)}</th></tr></thead>
            <tbody>${matrixRows}</tbody>
          </table>
        </div>
      </details>
    </section>`
    : "";
  return `<!doctype html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Slock Visual Testing</title>
  <style>
    body { margin: 24px; color: #141111; background: #fffaf0; font-family: system-ui, sans-serif; }
    header { max-width: 1180px; margin: 0 auto 24px; }
    h1 { margin: 0 0 8px; font-size: 32px; }
    h1 a { text-decoration: none; }
    h2 { margin: 0 0 12px; font-size: 20px; }
    a { color: #141111; font-weight: 700; }
    .summary { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0; }
    .pill { border: 2px solid #141111; background: #fff; padding: 6px 10px; box-shadow: 3px 3px 0 #141111; }
    .layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 18px; max-width: 1180px; margin: 0 auto; align-items: start; }
    nav.tree { position: sticky; top: 16px; max-height: calc(100vh - 32px); overflow-y: auto; border: 2px solid #141111; background: #fff; padding: 12px; box-shadow: 4px 4px 0 #141111; }
    nav.tree ul { list-style: none; margin: 0; padding-left: 12px; }
    nav.tree > ul { padding-left: 0; }
    nav.tree li { margin: 6px 0; }
    nav.tree a { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 6px; margin: 0 -6px; font-size: 12px; text-decoration: none; }
    nav.tree a:hover { background: #fffaf0; outline: 1px solid #141111; }
    nav.tree a code { color: #6b625f; font-size: 11px; }
    .caseGroup { margin-bottom: 24px; scroll-margin-top: 16px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 18px; }
    .case { border: 2px solid #141111; background: #fff; padding: 12px; box-shadow: 4px 4px 0 #141111; scroll-margin-top: 16px; }
    .caseHeader { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; margin-bottom: 10px; }
    .caseHeader span { display: block; margin-top: 4px; color: #6b625f; font-size: 13px; }
    .case img { width: 100%; display: block; border: 1px solid #141111; background: #fff; }
    .case img.sideBySide { width: 100%; height: auto; object-fit: contain; }
    .overlayComparison { margin-top: 10px; border: 1px solid #141111; background: #fffaf0; padding: 8px; }
    .overlayComparison summary { cursor: pointer; font-weight: 800; }
    .overlayControls { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 8px 0; font-size: 12px; }
    .overlayControls label { display: inline-flex; align-items: center; gap: 6px; font-weight: 700; }
    .overlayControls select, .overlayControls input { accent-color: #FFD440; }
    .overlayControls button { border: 1px solid #141111; background: #fff; padding: 3px 6px; font-size: 12px; font-weight: 800; box-shadow: 2px 2px 0 #141111; cursor: pointer; }
    .overlayCanvasWrap { overflow: auto; border: 1px solid #cfc7c2; background: #fff; }
    .overlayCanvasWrap canvas { display: block; width: 100%; height: auto; image-rendering: auto; touch-action: none; cursor: crosshair; }
    .overlayComparison[data-overlay-mode='split'] .overlayCanvasWrap canvas { cursor: ew-resize; }
    .overlayComparison[data-overlay-mode='align'] .overlayCanvasWrap canvas { cursor: move; }
    .rawCaptures { margin-top: 10px; }
    .rawCaptures summary { cursor: pointer; font-weight: 800; }
    .rawGrid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; margin-top: 8px; }
    .rawPanel { border: 1px solid #141111; background: #fffaf0; padding: 8px; overflow: auto; }
    .rawPanel strong { display: block; margin-bottom: 6px; font-size: 12px; }
    .rawPanel img { width: auto; max-width: 100%; height: auto; border: 1px solid #cfc7c2; }
    .visualMetadata { border: 1px solid #141111; background: #fffaf0; margin: 10px 0; padding: 8px; }
    .visualMetadata summary { cursor: pointer; font-weight: 800; }
    .metadataSection { margin-top: 8px; }
    .metadataSection h3 { margin: 0 0 6px; font-size: 13px; }
    .metadataGrid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }
    .metadataPanel { border: 1px solid #cfc7c2; background: #fff; padding: 8px; min-width: 0; }
    .metadataPanel strong { display: block; margin-bottom: 6px; font-size: 12px; }
    .metadataPanel pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 11px; }
    .caseMeta { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin: 10px 0; }
    .caseMeta div { border: 1px solid #141111; background: #fffaf0; padding: 6px; font-size: 12px; }
    .caseLinks { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
    .caseLinks a { border: 1px solid #141111; background: #fff; padding: 4px 6px; font-size: 12px; text-decoration: none; }
    .aiNotes { border: 1px solid #141111; background: #fffaf0; margin-top: 10px; padding: 8px; font-size: 13px; }
    .aiNotes summary { cursor: pointer; font-weight: 800; }
    .aiNotes ul { margin: 8px 0 0; padding-left: 18px; }
    .aiNotes li { margin: 4px 0; }
    .aiPending { color: #8a5f00; }
    .cropInfo { color: #6b625f; font-size: 12px; }
    .machineData { max-width: 1180px; margin: 24px auto; }
    .machineData details { border: 2px solid #141111; background: #fff; padding: 12px; box-shadow: 4px 4px 0 #141111; }
    .machineData summary { cursor: pointer; font-weight: 700; }
    .downloads { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; margin-top: 10px; }
    .downloads a { border: 2px solid #141111; background: #fff; padding: 8px; box-shadow: 3px 3px 0 #141111; text-decoration: none; }
    .matrix { overflow-x: auto; margin-top: 12px; }
    .matrix table { width: 100%; border-collapse: collapse; min-width: 860px; }
    .matrix th, .matrix td { border: 1px solid #141111; padding: 8px; text-align: left; vertical-align: top; font-size: 13px; }
    .matrix th { background: #FFD440; }
    .runMeta { color: #514946; margin: 0 0 10px; }
    .captureTime { display: block; margin-top: 4px; color: #514946; }
    .providerStatus { display: inline-block; border: 1px solid #141111; padding: 2px 5px; margin: 2px 4px 2px 0; background: #fffaf0; }
    .same { color: #2f6f1f; }
    .pass { color: #2f6f1f; }
    .basic-pass { color: #6f681f; }
    .different { color: #9d214a; }
    .missing { color: #b33224; }
    .pending { color: #8a5f00; }
    .active { color: #2f6f1f; }
    .unsupported { color: #6b625f; }
    code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
    @media (max-width: 760px) { .layout { grid-template-columns: 1fr; } nav.tree { position: static; max-height: none; } }
  </style>
</head>
<body>
  <header>
    <h1><a href="${xmlEscape(homeHref)}">Slock Visual Testing</a></h1>
    <p class="runMeta"><code>${xmlEscape(metadata.runId || "local")}</code> · <strong>${xmlEscape(metadata.baseline)}</strong> vs <strong>${xmlEscape(metadata.current)}</strong> · ${localizedTimestampHtml(metadata.generatedAt, { label: "Generated" })}</p>
    <div class="summary">
      <span class="pill">Total ${Number(metadata.summary.total || 0)}</span>
      <span class="pill">Same ${Number(metadata.summary.same || 0)}</span>
      <span class="pill">Pass ${Number(metadata.summary.pass || 0)}</span>
      <span class="pill">Basic pass ${Number(metadata.summary["basic-pass"] || 0)}</span>
      <span class="pill">Different ${Number(metadata.summary.different || 0)}</span>
      <span class="pill">Missing ${Number(metadata.summary.missing || 0)}</span>
      <span class="pill">AI ${Number(analysisSummary.ready || 0)}/${Number(analysisSummary.total || 0)} ready</span>
      ${metadata.componentMatrixSummary?.total ? `<span class="pill">Matrix ${Number(metadata.componentMatrixSummary?.active || 0)}/${Number(metadata.componentMatrixSummary?.total || 0)} active</span>` : ""}
      ${capturePills}
    </div>
  </header>
  <div class="layout">
    <nav class="tree" aria-label="Visual case categories">
      <strong>Cases</strong>
      ${tree}
    </nav>
    <main>${rows}</main>
  </div>
  <section class="machineData">
    <details>
      <summary>Downloads / machine data</summary>
      <div class="downloads">
        <a href="${xmlEscape(diffJsonHref)}">diff JSON</a>
        <a href="${xmlEscape(matrixHref)}">coverage matrix JSON</a>
        <a href="${xmlEscape(metadataHref)}">run metadata JSON</a>
      </div>
    </details>
  </section>
  ${matrixSection}
  ${localizedTimeScript()}
  ${overlayComparisonScript()}
</body>
</html>
`;
}

function caseCardHtml(item, latestPrefix) {
    const image = item.sideBySideImage
      ? `${latestPrefix}${path.relative(resultRoot, path.join(repoRoot, item.sideBySideImage))}`
      : "";
    const cropInfo = cropContractPreview(item);
    const baselineRaw = rawSizeText(item.baselineMetadata);
    const currentRaw = rawSizeText(item.currentMetadata);
    const baselineCaptured = providerCapturedHtml(item.baselineMetadata);
    const currentCaptured = providerCapturedHtml(item.currentMetadata);
    const baselineImage = item.baselineImage ? `${latestPrefix}${path.relative(resultRoot, path.join(repoRoot, item.baselineImage))}` : "";
    const currentImage = item.currentImage ? `${latestPrefix}${path.relative(resultRoot, path.join(repoRoot, item.currentImage))}` : "";
    const sideBySide = item.sideBySideImage ? `${latestPrefix}${path.relative(resultRoot, path.join(repoRoot, item.sideBySideImage))}` : "";
    const acceptance = item.acceptance ? `<p class="cropInfo">${xmlEscape(item.acceptance)}${item.rawStatus && item.rawStatus !== item.status ? ` · raw ${xmlEscape(item.rawStatus)}` : ""}</p>` : "";
    const captureType = normalizeCaptureType(item.captureType, item.id);
    return `<article class="case" id="case-${xmlEscape(anchorId(item.id))}">
      <div class="caseHeader">
        <div><code>${xmlEscape(item.id)}</code><span>${xmlEscape(captureTypeLabel(captureType))} · ${xmlEscape(casePath(item))}</span></div>
        <strong class="${xmlEscape(item.status)}">${xmlEscape(item.status)}</strong>
      </div>
      ${image ? `<a href="${xmlEscape(image)}"><img class="sideBySide" src="${xmlEscape(image)}" alt="${xmlEscape(item.id)} side by side"/></a>` : ""}
      <div class="caseMeta">
        <div><strong>${xmlEscape(providerLabel(item.baseline))}</strong><br/><code>${xmlEscape(baselineRaw)}</code>${baselineCaptured}</div>
        <div><strong>${xmlEscape(providerLabel(item.current))}</strong><br/><code>${xmlEscape(currentRaw)}</code>${currentCaptured}</div>
      </div>
      ${componentMetadataHtml(item)}
      <p>${similarityPreview(item.metrics)}</p>
      ${acceptance}
      ${cropInfo ? `<p class="cropInfo">${cropInfo}</p>` : ""}
      ${analysisHtml(item.analysis, latestPrefix)}
      ${overlayComparisonHtml(item, baselineImage, currentImage)}
      ${rawCapturesHtml(item, baselineImage, currentImage, baselineRaw, currentRaw)}
      <div class="caseLinks">
        ${baselineImage ? `<a href="${xmlEscape(baselineImage)}">${xmlEscape(providerLabel(item.baseline))} PNG</a>` : ""}
        ${currentImage ? `<a href="${xmlEscape(currentImage)}">${xmlEscape(providerLabel(item.current))} PNG</a>` : ""}
        ${sideBySide ? `<a href="${xmlEscape(sideBySide)}">Side-by-side PNG</a>` : ""}
      </div>
    </article>`;
}

function overlayComparisonHtml(item, baselineImage, currentImage) {
  if (!baselineImage || !currentImage) return "";
  const width = Number(item.metrics?.comparisonWidth || item.metrics?.baselineWidth || item.metrics?.currentWidth || 0);
  const height = Number(item.metrics?.comparisonHeight || item.metrics?.baselineHeight || item.metrics?.currentHeight || 0);
  const dimensions = width > 0 && height > 0 ? `data-overlay-width="${width}" data-overlay-height="${height}"` : "";
  const baselineLabel = providerLabel(item.baseline);
  const currentLabel = providerLabel(item.current);
  return `<details class="overlayComparison" open data-overlay-comparison data-baseline-src="${xmlEscape(baselineImage)}" data-current-src="${xmlEscape(currentImage)}" ${dimensions}>
    <summary>Overlay comparison · ${xmlEscape(baselineLabel)} under ${xmlEscape(currentLabel)}</summary>
    <div class="overlayControls">
      <label>Mode
        <select data-overlay-mode>
          <option value="blend">Blend</option>
          <option value="difference">Difference</option>
          <option value="split">Split drag</option>
          <option value="align">Align drag</option>
        </select>
      </label>
      <label>${xmlEscape(baselineLabel)} opacity
        <input data-overlay-baseline-opacity type="range" min="0" max="100" value="100" />
      </label>
      <code data-overlay-baseline-opacity-value>100%</code>
      <label>${xmlEscape(currentLabel)} opacity
        <input data-overlay-opacity type="range" min="0" max="100" value="50" />
      </label>
      <code data-overlay-opacity-value>50%</code>
      <label>Snap
        <input data-overlay-snap type="checkbox" checked />
      </label>
      <button type="button" data-overlay-reset>Reset</button>
      <span class="cropInfo">Split <code data-overlay-split-value>50%</code></span>
      <span class="cropInfo">Offset <code data-overlay-offset-value>0px, 0px</code></span>
      <span class="cropInfo" data-overlay-status>${width > 0 && height > 0 ? `Canvas ${width}x${height}` : "Canvas auto"}</span>
    </div>
    <div class="overlayCanvasWrap">
      <canvas data-overlay-canvas aria-label="${xmlEscape(item.id)} overlay comparison"></canvas>
    </div>
  </details>`;
}

function providerCapturedHtml(metadata) {
  return metadata?.capturedAt
    ? `<span class="captureTime">${localizedTimestampHtml(metadata.capturedAt, { label: "Captured" })}</span>`
    : "";
}

function componentMetadataHtml(item) {
  if (normalizeCaptureType(item.captureType, item.id) !== componentFixtureCaptureType) return "";
  const sections = [
    ["typography", "Typography"],
    ["styleTokens", "Style tokens"],
    ["computedStyles", "Computed styles"],
  ].map(([field, label]) => metadataComparisonSection(field, label, item.baseline, item.current, item.baselineMetadata, item.currentMetadata))
    .filter(Boolean)
    .join("");
  if (!sections) {
    return `<details class="visualMetadata">
      <summary>Component metadata</summary>
      <p class="cropInfo">No typography/style metadata captured yet. Providers can emit <code>typography</code>, <code>styleTokens</code>, or <code>computedStyles</code> in their metadata JSON.</p>
    </details>`;
  }
  return `<details class="visualMetadata" open>
    <summary>Component metadata</summary>
    ${sections}
  </details>`;
}

function metadataComparisonSection(field, label, baseline, current, baselineMetadata, currentMetadata) {
  const baselineValue = baselineMetadata?.[field];
  const currentValue = currentMetadata?.[field];
  if (baselineValue === undefined && currentValue === undefined) return "";
  return `<section class="metadataSection">
    <h3>${xmlEscape(label)}</h3>
    <div class="metadataGrid">
      ${metadataValuePanel(providerLabel(baseline), baselineValue)}
      ${metadataValuePanel(providerLabel(current), currentValue)}
    </div>
  </section>`;
}

function metadataValuePanel(label, value) {
  const content = value === undefined
    ? "not captured"
    : JSON.stringify(value, null, 2);
  return `<div class="metadataPanel">
    <strong>${xmlEscape(label)}</strong>
    <pre>${xmlEscape(content)}</pre>
  </div>`;
}

function analysisHtml(analysis, latestPrefix) {
  if (!analysis) {
    return `<div class="aiNotes aiPending"><strong>AI diff notes</strong><br/>analysis pending</div>`;
  }
  if (analysis.status !== "ready") {
    return `<div class="aiNotes aiPending"><strong>AI diff notes</strong><br/>${xmlEscape(analysis.error || "analysis unavailable")}</div>`;
  }
  const notes = (analysis.summary || []).slice(0, 3).map((note) => `<li>${xmlEscape(note)}</li>`).join("");
  const fixes = (analysis.suggestedFixes || []).map((fix) => `<li>${xmlEscape(fix)}</li>`).join("");
  return `<details class="aiNotes" open>
    <summary>AI diff notes · ${xmlEscape(analysis.severity || "unknown")}${analysis.suggestedOwner ? ` · ${xmlEscape(analysis.suggestedOwner)}` : ""}</summary>
    <ul>${notes || "<li>No concise notes returned.</li>"}</ul>
    ${fixes ? `<strong>Suggested fixes</strong><ul>${fixes}</ul>` : ""}
    <p><a href="${xmlEscape(`${latestPrefix}analysis-summary.json`)}">analysis summary</a></p>
  </details>`;
}

function rawCapturesHtml(item, baselineImage, currentImage, baselineRaw, currentRaw) {
  if (!baselineImage && !currentImage) return "";
  const baselineLabel = providerLabel(item.baseline);
  const currentLabel = providerLabel(item.current);
  const panels = [
    baselineImage
      ? `<div class="rawPanel"><strong>${xmlEscape(baselineLabel)} raw · ${xmlEscape(baselineRaw)}</strong><a href="${xmlEscape(baselineImage)}"><img src="${xmlEscape(baselineImage)}" alt="${xmlEscape(item.id)} ${xmlEscape(baselineLabel)} raw capture"/></a></div>`
      : "",
    currentImage
      ? `<div class="rawPanel"><strong>${xmlEscape(currentLabel)} raw · ${xmlEscape(currentRaw)}</strong><a href="${xmlEscape(currentImage)}"><img src="${xmlEscape(currentImage)}" alt="${xmlEscape(item.id)} ${xmlEscape(currentLabel)} raw capture"/></a></div>`
      : "",
  ].filter(Boolean).join("");
  return `<details class="rawCaptures">
    <summary>Raw captures · original provider size</summary>
    <div class="rawGrid">${panels}</div>
  </details>`;
}

function groupedCaseCards(results, latestPrefix) {
  const groups = [];
  const byCategory = new Map();
  for (const item of results || []) {
    const captureType = normalizeCaptureType(item.captureType, item.id);
    const category = `${captureTypeLabel(captureType)} / ${caseCategory(item)}`;
    if (!byCategory.has(category)) {
      const group = { category, items: [] };
      byCategory.set(category, group);
      groups.push(group);
    }
    byCategory.get(category).items.push(item);
  }
  return groups;
}

function rawSizeText(metadata) {
  const rect = metadata?.crop?.rect;
  if (rect?.width && rect?.height) return `${Math.round(Number(rect.width))}×${Math.round(Number(rect.height))}`;
  const viewport = metadata?.viewport;
  if (viewport?.width && viewport?.height) return `${Math.round(Number(viewport.width))}×${Math.round(Number(viewport.height))}`;
  return "n/a";
}

function categoryTreeHtml(results, hrefPrefix = "") {
  const root = { children: new Map(), cases: [] };
  for (const item of results || []) {
    const parts = casePath(item).split("/").filter(Boolean);
    const leaf = parts.pop() || item.id;
    let node = root;
    for (const part of parts) {
      if (!node.children.has(part)) node.children.set(part, { children: new Map(), cases: [] });
      node = node.children.get(part);
    }
    node.cases.push({ ...item, label: leaf });
  }
  return treeNodeHtml(root, hrefPrefix);
}

function treeNodeHtml(node, hrefPrefix) {
  const groups = [...node.children.entries()];
  const cases = [...node.cases];
  const children = [
    ...groups.map(([name, child]) => `<li><strong>${xmlEscape(name)}</strong>${treeNodeHtml(child, hrefPrefix)}</li>`),
    ...cases.map((item) => `<li><a href="#case-${xmlEscape(anchorId(item.id))}"><span>${xmlEscape(item.label)}</span><code>${xmlEscape(item.status)}</code></a></li>`),
  ].join("");
  return `<ul>${children}</ul>`;
}

function componentMatrixHtml(matrix) {
  return (matrix || []).map((row) => {
    const captureType = normalizeCaptureType(row.captureType, row.id);
    const providers = Object.entries(row.providers || {}).map(([provider, entry]) => {
      const components = entry.components?.length ? ` ${entry.components.join(", ")}` : "";
      const reason = entry.reason ? `<br/><small>${xmlEscape(entry.reason)}</small>` : "";
      return `<span class="providerStatus ${xmlEscape(entry.status)}"><strong>${xmlEscape(provider)}</strong>: ${xmlEscape(entry.status)}${xmlEscape(components)}</span>${reason}`;
    }).join("<br/>");
    const acceptance = row.comparison?.acceptance ? `<br/><small>${xmlEscape(row.comparison.acceptance)}</small>` : "";
    const rawStatus = row.comparison?.rawStatus && row.comparison.rawStatus !== row.comparison.status ? `<br/><small>raw ${xmlEscape(row.comparison.rawStatus)}</small>` : "";
    const comparison = row.comparison
      ? `${xmlEscape(row.comparison.status)}${rawStatus}${acceptance}${row.comparison.rgbSimilarity !== null ? `<br/><code>RGB ${(Number(row.comparison.rgbSimilarity) * 100).toFixed(1)}%</code><br/><code>Pixel ${(Number(row.comparison.pixelPerfectSimilarity || 0) * 100).toFixed(2)}%</code>` : ""}`
      : "pending";
    return `<tr>
      <td><strong>${xmlEscape(captureTypeLabel(captureType))}</strong><br/><code>${xmlEscape(captureType)}</code></td>
      <td>${xmlEscape(row.category || row.group || "")}</td>
      <td><code>${xmlEscape(row.id)}</code><br/>${xmlEscape(row.title || "")}</td>
      <td class="${xmlEscape(row.status || "")}">${xmlEscape(row.status || "")}</td>
      <td>${providers}</td>
      <td>${comparison}</td>
    </tr>`;
  }).join("\n");
}

function captureTypeSummaryPills(summary = {}) {
  const rows = Object.entries(summary.captureTypes || {})
    .filter(([, value]) => Number(value.total || 0) > 0)
    .map(([captureType, value]) =>
      `<span class="pill">${xmlEscape(captureTypeLabel(captureType))} ${Number(value.active || 0)}/${Number(value.total || 0)} active</span>`,
    );
  return rows.join("");
}

function siteIndexJson(metadata, runs) {
  return {
    version: 1,
    name: "Slock Visual Testing",
    generatedAt: metadata.generatedAt,
    latestRunId: metadata.runId,
    latest: runs[0] || null,
    componentMatrixSummary: metadata.componentMatrixSummary || {},
    runs,
  };
}

function siteLlmsText(metadata, runs) {
  const matrixSummary = metadata.componentMatrixSummary || {};
  const analysisSummary = metadata.analysisSummary || {};
  const providerLines = Object.entries(matrixSummary.providers || {}).map(
    ([provider, value]) => `- ${provider}: active ${Number(value.active || 0)}, pending ${Number(value.pending || 0)}, unsupported ${Number(value.unsupported || 0)}`,
  );
  const captureTypeLines = Object.entries(matrixSummary.captureTypes || {})
    .filter(([, value]) => Number(value.total || 0) > 0)
    .map(([captureType, value]) => `- ${captureTypeLabel(captureType)} (${captureType}): active ${Number(value.active || 0)}, pending ${Number(value.pending || 0)}, total ${Number(value.total || 0)}`);
  const pendingRows = (metadata.componentMatrix || []).filter((row) => row.status !== "active");
  const lines = [
    "# Slock Visual Testing",
    "",
    `Generated: ${utcTimestampText(metadata.generatedAt)}`,
    `Latest run: ${metadata.runId}`,
    `Latest commit: ${metadata.commit}`,
    metadata.commitSubject ? `Commit subject: ${metadata.commitSubject}` : null,
    `Providers: ${metadata.baseline} vs ${metadata.current}`,
    `Summary: total ${Number(metadata.summary.total || 0)}, same ${Number(metadata.summary.same || 0)}, pass ${Number(metadata.summary.pass || 0)}, basic-pass ${Number(metadata.summary["basic-pass"] || 0)}, different ${Number(metadata.summary.different || 0)}, missing ${Number(metadata.summary.missing || 0)}`,
    `AI analysis: ready ${Number(analysisSummary.ready || 0)} / total ${Number(analysisSummary.total || 0)}, pending ${Number(analysisSummary.pending || 0)}`,
    `Visual coverage matrix: active ${Number(matrixSummary.active || 0)} / total ${Number(matrixSummary.total || 0)}, pending ${Number(matrixSummary.pending || 0)}, skipped ${Number(matrixSummary.skipped || 0)}`,
    `Capture taxonomy: components use ${componentFixtureCaptureType}; real screens use ${realScreenCaptureType}`,
    "",
    "## Capture Type Coverage",
    "",
    ...(captureTypeLines.length ? captureTypeLines : ["- none"]),
    "",
    "## Provider Coverage",
    "",
    ...providerLines,
    "",
    "## Pending Coverage Rows",
    "",
    ...(pendingRows.length ? pendingRows.map((row) => `- ${row.id}: ${row.notes || row.title || row.status}`) : ["- none"]),
    "",
    "## Runs",
    "",
    ...runs.map((run) => `- ${run.runId}: ${run.baseline} vs ${run.current}, total ${Number(run.summary?.total || 0)}, pass ${Number(run.summary?.pass || 0)}, basic-pass ${Number(run.summary?.["basic-pass"] || 0)}, different ${Number(run.summary?.different || 0)}, matrix ${run.componentMatrix}, report ${run.report}`),
    "",
    "## Baseline Gate",
    "",
    "Only real product component paths should be included in this report. Pending, fake, preview-only, or mis-scaled provider cases should be excluded from similarity accounting.",
    "",
  ].filter((line) => line !== null);
  return `${lines.join("\n")}\n`;
}

function siteHomeHtml(metadata, runs) {
  const rows = runs.map((run) => `<tr>
    <td><code>${xmlEscape(run.runId)}</code><br/>${xmlEscape(run.commitSubject || "")}</td>
    <td>${localizedTimestampHtml(run.generatedAt)}</td>
    <td>${xmlEscape(run.baseline)} vs ${xmlEscape(run.current)}</td>
    <td>Total ${Number(run.summary?.total || 0)}<br/>Pass ${Number(run.summary?.pass || 0)}<br/>Basic pass ${Number(run.summary?.["basic-pass"] || 0)}<br/>Different ${Number(run.summary?.different || 0)}<br/>Missing ${Number(run.summary?.missing || 0)}</td>
    <td><a href="${xmlEscape(run.href)}">查看详情</a></td>
  </tr>`).join("\n");
  const latestRun = runs[0] || runSummary(metadata, "latest/");
  const latestSummary = latestRun.summary || {};
  const analysisSummary = metadata.analysisSummary || {};
  const capturePills = captureTypeSummaryPills(metadata.componentMatrixSummary);
  return `<!doctype html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Slock Visual Testing Runs</title>
  <style>
    body { margin: 24px; color: #141111; background: #fffaf0; font-family: system-ui, sans-serif; }
    main { max-width: 1080px; margin: 0 auto; }
    h1 { margin: 0 0 8px; font-size: 32px; }
    a { color: #141111; font-weight: 700; }
    .hero { border: 2px solid #141111; background: #fff; padding: 18px; margin: 18px 0; box-shadow: 4px 4px 0 #141111; }
    .hero h2 { margin: 0 0 8px; font-size: 22px; }
    .hero p { margin: 0 0 14px; color: #514946; }
    .primary { display: inline-block; border: 2px solid #141111; background: #FFD440; color: #141111; padding: 10px 14px; box-shadow: 3px 3px 0 #141111; text-decoration: none; }
    .meta { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 0 0; }
    .runMeta { color: #514946; margin: 0 0 14px; }
    .downloads { border: 2px solid #141111; background: #fff; padding: 12px; margin: 18px 0; box-shadow: 4px 4px 0 #141111; }
    .downloads h2 { margin: 0 0 8px; font-size: 18px; }
    .links { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 0; }
    .pill { border: 2px solid #141111; background: #fff; padding: 6px 10px; box-shadow: 3px 3px 0 #141111; }
    table { width: 100%; border-collapse: collapse; background: #fff; box-shadow: 4px 4px 0 #141111; }
    th, td { border: 2px solid #141111; padding: 10px; text-align: left; vertical-align: top; }
    th { background: #FFD440; }
    code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  </style>
</head>
<body>
  <main>
    <h1>Slock Visual Testing</h1>
    <section class="hero">
      <h2>最新视觉对比</h2>
      <p><code>${xmlEscape(metadata.runId)}</code> · ${xmlEscape(metadata.baseline)} vs ${xmlEscape(metadata.current)}</p>
      <p class="runMeta">${localizedTimestampHtml(metadata.generatedAt, { label: "Generated" })}</p>
      <a class="primary" href="${xmlEscape(latestRun.href || "latest/")}">查看详情</a>
      <div class="meta">
        <span class="pill">Total ${Number(latestSummary.total || 0)}</span>
        <span class="pill">Pass ${Number(latestSummary.pass || 0)}</span>
        <span class="pill">Basic pass ${Number(latestSummary["basic-pass"] || 0)}</span>
        <span class="pill">Different ${Number(latestSummary.different || 0)}</span>
        <span class="pill">Missing ${Number(latestSummary.missing || 0)}</span>
        <span class="pill">AI ${Number(analysisSummary.ready || 0)}/${Number(analysisSummary.total || 0)} ready</span>
        ${capturePills}
      </div>
    </section>
    <section class="downloads">
      <h2>下载 / 机器数据</h2>
      <p>给自动化、LLM 摘要和调试使用；普通查看只需要点上面的“查看详情”。</p>
      <div class="links">
        <a class="pill" href="index.json">index.json</a>
        <a class="pill" href="llms.txt">llms.txt</a>
        <a class="pill" href="latest/component-matrix.json">coverage matrix JSON</a>
        <a class="pill" href="latest/metadata.json">latest metadata</a>
      </div>
    </section>
    <h2>历史记录</h2>
    <table>
      <thead><tr><th>Commit</th><th>Generated</th><th>Providers</th><th>Diff Summary</th><th>Action</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  </main>
  ${localizedTimeScript()}
</body>
</html>
`;
}

function mergeSiteIntoPublishWorktree(sourceRoot, targetSubdir, runId, options = {}) {
  ensureDir(targetSubdir);
  const runSource = path.join(sourceRoot, "runs", runId);
  const runTarget = path.join(targetSubdir, "runs", runId);
  const latestTarget = path.join(targetSubdir, "latest");
  if (options.resetRuns) {
    fs.rmSync(path.join(targetSubdir, "runs"), { recursive: true, force: true });
  }
  fs.rmSync(runTarget, { recursive: true, force: true });
  fs.rmSync(latestTarget, { recursive: true, force: true });
  copyDirIntoSite(runSource, runTarget);
  copyDirIntoSite(path.join(sourceRoot, "latest"), latestTarget);

  const latestMetadata = readJson(path.join(sourceRoot, "metadata.json"));
  const existingIndexPath = path.join(targetSubdir, "index.json");
  const existingRuns = readExistingSiteRuns(targetSubdir, options);
  const nextRun = runSummary(latestMetadata, `runs/${runId}/`);
  const runs = [nextRun, ...existingRuns.filter((run) => run.runId !== runId)];
  writeJson(path.join(targetSubdir, "metadata.json"), latestMetadata);
  writeJson(existingIndexPath, siteIndexJson(latestMetadata, runs));
  fs.writeFileSync(path.join(targetSubdir, "llms.txt"), siteLlmsText(latestMetadata, runs));
  fs.writeFileSync(path.join(targetSubdir, "index.html"), siteHomeHtml(latestMetadata, runs));
}

function readExistingSiteRuns(targetSubdir, options = {}) {
  if (options.resetRuns) return [];
  const runsById = new Map();
  const existingIndexPath = path.join(targetSubdir, "index.json");
  if (fs.existsSync(existingIndexPath)) {
    for (const run of readJson(existingIndexPath).runs || []) {
      if (run?.runId) runsById.set(run.runId, run);
    }
  }
  const runsRoot = path.join(targetSubdir, "runs");
  if (fs.existsSync(runsRoot)) {
    for (const entry of fs.readdirSync(runsRoot, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const metadataPath = path.join(runsRoot, entry.name, "metadata.json");
      if (!fs.existsSync(metadataPath)) continue;
      const metadata = { ...readJson(metadataPath), runId: readJson(metadataPath).runId || entry.name };
      if (!runsById.has(metadata.runId)) {
        runsById.set(metadata.runId, runSummary(metadata, `runs/${entry.name}/`));
      }
    }
  }
  return [...runsById.values()].sort((a, b) => String(b.generatedAt || "").localeCompare(String(a.generatedAt || "")));
}

function defaultPublishRunId(flags) {
  const sourceCommit = gitOutput(sourceRepoRoot(flags), ["rev-parse", "--short", "HEAD"]) || "local";
  const timestamp = new Date().toISOString().replace(/\D/g, "").slice(0, 14);
  return `${sourceCommit}-${timestamp}`;
}

function publishGhPages(flags) {
  const publishRepoRoot = path.resolve(flags.repoDir || flags["repo-dir"] || repoRoot);
  const remote = String(flags.remote || "origin");
  const branch = String(flags.branch || "gh-pages");
  const subdir = String(flags.subdir || "visual-testing").replace(/^\/+|\/+$/g, "");
  const runId = normalizeRunId(flags.runId || flags["run-id"] || defaultPublishRunId(flags));
  const commitSubject = sourceCommitSubject(flags);
  const allowPartialPublish = Boolean(flags["allow-partial-publish"]);
  if (!allowPartialPublish && !hasCaseSelection(flags)) {
    diff(flags);
  }
  const targetRoot = site({ ...flags, runId, commitSubject });
  assertPublishSiteScope(targetRoot, flags);
  const dryRun = Boolean(flags["dry-run"]);
  const resetRuns = Boolean(flags["reset-runs"]);
  const url = inferPagesUrl(remote, subdir, publishRepoRoot);
  if (dryRun) {
    const resetSuffix = resetRuns ? " and reset existing runs" : "";
    console.log(`publish-gh-pages dry-run: would publish ${path.relative(repoRoot, targetRoot)} to ${publishRepoRoot} ${remote}/${branch}:${subdir}${resetSuffix}`);
    if (url) console.log(`publish-gh-pages dry-run: expected url ${url}`);
    return;
  }

  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "slock-visual-gh-pages-"));
  const publishBranch = `slock-visual-gh-pages-${Date.now()}`;
  const branchExists = remoteBranchExists(publishRepoRoot, remote, branch);
  try {
    if (branchExists) {
      run("git", ["worktree", "add", "-B", publishBranch, tempDir, `${remote}/${branch}`], { cwd: publishRepoRoot });
    } else {
      run("git", ["worktree", "add", "--detach", tempDir, "HEAD"], { cwd: publishRepoRoot });
      run("git", ["checkout", "--orphan", publishBranch], { cwd: tempDir });
      run("git", ["rm", "-r", "-f", "--ignore-unmatch", "."], { cwd: tempDir });
      removeWorktreeContents(tempDir);
    }

    const targetSubdir = path.join(tempDir, subdir);
    mergeSiteIntoPublishWorktree(targetRoot, targetSubdir, runId, { resetRuns });
    const rootIndex = path.join(tempDir, "index.html");
    fs.writeFileSync(rootIndex, `<!doctype html><meta charset="utf-8"><title>Slock</title><a href="./${subdir}/">Slock Visual Testing</a>\n`);
    run("git", ["add", subdir, "index.html"], { cwd: tempDir });
    const status = spawnSync("git", ["status", "--porcelain"], { cwd: tempDir, encoding: "utf8" }).stdout.trim();
    if (!status) {
      console.log("publish-gh-pages: no changes to publish");
      return;
    }
    const message = String(flags.message || `chore: publish visual testing report ${new Date().toISOString()}`);
    run("git", ["commit", "-m", message], { cwd: tempDir });
    run("git", ["push", remote, `HEAD:${branch}`], { cwd: tempDir });
    console.log(`publish-gh-pages: pushed ${subdir} to ${remote}/${branch}${resetRuns ? " (reset runs)" : ""}`);
    if (url) console.log(`publish-gh-pages: ${url}`);
  } finally {
    const removeResult = spawnSync("git", ["worktree", "remove", tempDir], { cwd: publishRepoRoot, stdio: "ignore" });
    if (removeResult.status !== 0) {
      console.warn(`publish-gh-pages: kept temporary worktree ${tempDir}`);
    }
    spawnSync("git", ["branch", "-D", publishBranch], { cwd: publishRepoRoot, stdio: "ignore" });
  }
}

function assertPublishSiteScope(targetRoot, flags) {
  if (flags["allow-partial-publish"]) return;
  const metadataPath = path.join(targetRoot, "metadata.json");
  if (!fs.existsSync(metadataPath)) throw new Error(`publish-gh-pages: missing site metadata at ${metadataPath}`);
  const metadata = readJson(metadataPath);
  const actual = Number(metadata.summary?.total || 0);
  const expected = expectedFullPublishCaseCount(flags);
  if (actual < expected) {
    const selectionText = hasCaseSelection(flags) ? " with a case/category filter" : "";
    throw new Error(
      `publish-gh-pages: refusing to publish partial visual site${selectionText}: summary.total=${actual}, expected at least ${expected}. ` +
        "Run full diff/report/site first, or pass --allow-partial-publish for an explicit partial publish.",
    );
  }
}

function removeWorktreeContents(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === ".git") continue;
    fs.rmSync(path.join(dir, entry.name), { recursive: true, force: true });
  }
}

function remoteBranchExists(cwd, remote, branch) {
  const fetchResult = spawnSync("git", ["fetch", remote, branch], { cwd, stdio: "ignore" });
  if (fetchResult.status === 0) return true;
  const showRef = spawnSync("git", ["show-ref", "--verify", "--quiet", `refs/remotes/${remote}/${branch}`], { cwd, stdio: "ignore" });
  if (showRef.status === 0) return true;
  const lsRemote = spawnSync("git", ["ls-remote", "--exit-code", "--heads", remote, branch], { cwd, stdio: "ignore" });
  return lsRemote.status === 0;
}

function inferPagesUrl(remote, subdir, publishRepoRoot = repoRoot) {
  const remoteInfo = spawnSync("git", ["remote", "get-url", remote], { cwd: publishRepoRoot, encoding: "utf8" });
  const remoteUrl = remoteInfo.status === 0 ? remoteInfo.stdout.trim() : remote;
  const match = remoteUrl.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/);
  if (!match) return null;
  return `https://${match[1]}.github.io/${match[2]}/${subdir}/`;
}

function similarityPreview(metrics) {
  if (!metrics) return "";
  const rgb = Number(metrics.rgbSimilarity || 0) * 100;
  const pixel = Number(metrics.pixelPerfectSimilarity || 0) * 100;
  const mismatch = Number(metrics.pixelMismatchRatio || 0) * 100;
  const rawSize =
    Number.isFinite(Number(metrics.baselineWidth)) && Number.isFinite(Number(metrics.currentWidth))
      ? `<br/><code>Raw ${Number(metrics.baselineWidth)}×${Number(metrics.baselineHeight)} / ${Number(metrics.currentWidth)}×${Number(metrics.currentHeight)}</code>`
      : "";
  const canvasSize =
    Number.isFinite(Number(metrics.comparisonWidth))
      ? `<br/><code>Canvas ${Number(metrics.comparisonWidth)}×${Number(metrics.comparisonHeight)}</code>`
      : "";
  const compositor = metrics.compositor ? `<br/><code>Compositor ${xmlEscape(metrics.compositor)}</code>` : "";
  const displayMode = metrics.displayMode ? `<br/><code>Display ${xmlEscape(metrics.displayMode)}</code>` : "";
  const metricMode = metrics.metricMode ? `<br/><code>Metrics ${xmlEscape(metrics.metricMode)}</code>` : "";
  return `<code>RGB ${rgb.toFixed(1)}%</code><br/><code>Pixel ${pixel.toFixed(2)}%</code><br/><code>Mismatch ${mismatch.toFixed(2)}%</code>${rawSize}${canvasSize}${compositor}${displayMode}${metricMode}`;
}

function cropContractPreview(item) {
  const baseline = item?.baselineMetadata?.crop;
  const current = item?.currentMetadata?.crop;
  if (!baseline && !current) return "";
  const baselineContract = baseline?.contract || "unknown";
  const currentContract = current?.contract || "unknown";
  const baselineRect = baseline?.rect ? `${Math.round(Number(baseline.rect.width))}×${Math.round(Number(baseline.rect.height))}` : "n/a";
  const currentRect = current?.rect ? `${Math.round(Number(current.rect.width))}×${Math.round(Number(current.rect.height))}` : "n/a";
  return `<code>Crop ${xmlEscape(baselineContract)} ${xmlEscape(baselineRect)} / ${xmlEscape(currentContract)} ${xmlEscape(currentRect)}</code>`;
}

function imagePreview(relativePath) {
  if (!relativePath) return "";
  const previewPath = path.relative(resultRoot, path.join(repoRoot, relativePath));
  return `<br/><img src="${xmlEscape(previewPath)}" alt="capture"/>`;
}

function anchorId(value) {
  return String(value || "case").replace(/[^a-zA-Z0-9_-]+/g, "-");
}

function validate(flags) {
  const manifest = loadManifest(flags);
  const errors = [];
  const ids = new Set();
  for (const visualCase of manifest.cases || []) {
    if (!visualCase.id) errors.push("case missing id");
    if (visualCase.id && ids.has(visualCase.id)) errors.push(`duplicate case id: ${visualCase.id}`);
    if (visualCase.id) ids.add(visualCase.id);
    if (visualCase.captureType !== undefined && !captureTypeValues.has(String(visualCase.captureType))) {
      errors.push(`${visualCase.id}: captureType must be ${[...captureTypeValues].join(" or ")}`);
    }
    if (visualCase.skip !== undefined && typeof visualCase.skip !== "boolean" && (typeof visualCase.skip !== "object" || Array.isArray(visualCase.skip))) {
      errors.push(`${visualCase.id}: skip must be a boolean or object`);
    }
    if (!visualCase.fixture) errors.push(`${visualCase.id}: missing fixture`);
    if (!Array.isArray(visualCase.variants) || visualCase.variants.length === 0) {
      errors.push(`${visualCase.id}: variants must include at least one state variant`);
    } else {
      const variantIds = new Set();
      for (const variant of visualCase.variants) {
        if (!variant.id) errors.push(`${visualCase.id}: variant missing id`);
        if (variant.id && variantIds.has(variant.id)) errors.push(`${visualCase.id}: duplicate variant id: ${variant.id}`);
        if (variant.id) variantIds.add(variant.id);
        if (variant.interactions && !Array.isArray(variant.interactions)) {
          errors.push(`${visualCase.id}.${variant.id}: interactions must be an array`);
        }
      }
    }
    if (!visualCase.viewport?.width || !visualCase.viewport?.height || !visualCase.viewport?.density) {
      errors.push(`${visualCase.id}: viewport must include width, height, density`);
    }
    if (!visualCase.capture?.selector && !visualCase.selector) errors.push(`${visualCase.id}: missing capture selector`);
    if (!visualCase.tolerance?.pixelRatio) errors.push(`${visualCase.id}: missing tolerance.pixelRatio`);
  }
  if (errors.length > 0) {
    for (const error of errors) console.error(`validate: ${error}`);
    throw new Error(`Visual Testing Spec validation failed with ${errors.length} error(s)`);
  }
  console.log(`validate: ${manifest.cases.length} cases ok`);
}

function clean() {
  fs.rmSync(artifactRoot, { recursive: true, force: true });
  fs.rmSync(resultRoot, { recursive: true, force: true });
  console.log("clean: removed visual testing artifacts/results");
}

function parseKmpSlockColors(kmpTokenFile) {
  const source = readTextIfExists(kmpTokenFile);
  const colors = {};
  const colorBlock = source.match(/internal\s+object\s+SlockColor\s*\{([\s\S]*?)\n\}/);
  if (!colorBlock) return colors;
  const colorPattern = /val\s+([A-Za-z0-9_]+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)/g;
  for (const match of colorBlock[1].matchAll(colorPattern)) {
    colors[match[1]] = normalizeHex(match[2]);
  }
  return colors;
}

function tokenNamesForHex(sharedColors) {
  const byHex = new Map();
  for (const [name, hex] of Object.entries(sharedColors || {})) {
    const normalized = normalizeHex(hex);
    if (!normalized) continue;
    if (!byHex.has(normalized)) byHex.set(normalized, []);
    byHex.get(normalized).push(name);
  }
  return byHex;
}

function lineNumberForOffset(source, offset) {
  return source.slice(0, offset).split(/\n/).length;
}

function scanHardcodedColorUsage({ root, files, allowedFiles, sharedColors, label }) {
  const knownByHex = tokenNamesForHex(sharedColors);
  const usage = [];
  for (const file of files) {
    const relative = path.relative(root, file);
    const source = fs.readFileSync(file, "utf8");
    const hexPattern = /(?<![A-Za-z0-9])#([0-9A-Fa-f]{6})(?:[0-9A-Fa-f]{2})?(?![A-Za-z0-9])/g;
    for (const match of source.matchAll(hexPattern)) {
      const hex = normalizeHex(match[0]);
      if (!hex) continue;
      usage.push({
        provider: label,
        file: relative,
        line: lineNumberForOffset(source, match.index),
        value: hex,
        tokenNames: knownByHex.get(hex) || [],
        allowedFile: allowedFiles.has(relative),
      });
    }
    const colorPattern = /Color\(\s*0x([0-9A-Fa-f]{8})\s*\)/g;
    for (const match of source.matchAll(colorPattern)) {
      const hex = normalizeHex(match[1]);
      if (!hex) continue;
      usage.push({
        provider: label,
        file: relative,
        line: lineNumberForOffset(source, match.index),
        value: hex,
        tokenNames: knownByHex.get(hex) || [],
        allowedFile: allowedFiles.has(relative),
      });
    }
  }
  return usage;
}

function tokenAuditClassificationFor(item, classifications) {
  const providerEntries = classifications?.unknownColors?.[item.provider] || [];
  return providerEntries.find((entry) => {
    if (entry.file && entry.file !== item.file) return false;
    if (entry.value && normalizeHex(entry.value) !== item.value) return false;
    return true;
  }) || null;
}

function summarizeTokenUsage(usage, classifications) {
  const unknown = usage.filter((item) => !item.allowedFile && item.tokenNames.length === 0);
  const knownHardcoded = usage.filter((item) => !item.allowedFile && item.tokenNames.length > 0);
  const allowed = usage.filter((item) => item.allowedFile);
  const classifiedUnknown = unknown.filter((item) => tokenAuditClassificationFor(item, classifications));
  const unclassifiedUnknown = unknown.filter((item) => !tokenAuditClassificationFor(item, classifications));
  const unknownByFile = countBy(unknown, (item) => item.file);
  const unclassifiedUnknownByFile = countBy(unclassifiedUnknown, (item) => item.file);
  const knownByFile = countBy(knownHardcoded, (item) => item.file);
  return {
    total: usage.length,
    allowed: allowed.length,
    knownTokenHardcoded: knownHardcoded.length,
    unknownHardcoded: unknown.length,
    classifiedUnknownHardcoded: classifiedUnknown.length,
    unclassifiedUnknownHardcoded: unclassifiedUnknown.length,
    unknownTopFiles: topEntries(unknownByFile, 10),
    unclassifiedUnknownTopFiles: topEntries(unclassifiedUnknownByFile, 10),
    knownTokenTopFiles: topEntries(knownByFile, 10),
  };
}

function countBy(items, keyFn) {
  const counts = new Map();
  for (const item of items) {
    const key = keyFn(item);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return counts;
}

function topEntries(map, limit) {
  return [...map.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, limit)
    .map(([key, count]) => ({ key, count }));
}

function renderTokenAuditMarkdown(audit) {
  const sourceRows = audit.sourceOfTruth.results
    .map((item) => `| ${item.webToken} | ${item.webHex || "-"} | ${item.kmpName || "-"} | ${item.kmpHex || "-"} | ${item.status} |`)
    .join("\n");
  const usageRows = Object.entries(audit.usage)
    .map(([provider, summary]) => `| ${provider} | ${summary.total} | ${summary.allowed} | ${summary.knownTokenHardcoded} | ${summary.unknownHardcoded} | ${summary.classifiedUnknownHardcoded} | ${summary.unclassifiedUnknownHardcoded} |`)
    .join("\n");
  const unknownSections = Object.entries(audit.usage)
    .map(([provider, summary]) => {
      const rows = summary.unknownTopFiles.map((item) => `- ${item.key}: ${item.count}`).join("\n") || "- none";
      const unclassifiedRows = summary.unclassifiedUnknownTopFiles.map((item) => `- ${item.key}: ${item.count}`).join("\n") || "- none";
      return `### ${provider} Unknown Hardcoded Color Files\n${rows}\n\n#### ${provider} Unclassified Unknown Hardcoded Color Files\n${unclassifiedRows}`;
    })
    .join("\n\n");
  return `# Slock Token Audit

Generated: ${audit.generatedAt}

## Source Of Truth

Status: ${audit.sourceOfTruth.status}

| Web token | Web hex | KMP name | KMP hex | Status |
| --- | --- | --- | --- | --- |
${sourceRows}

## Hardcoded Usage Summary

Allowed files include palette/token demo surfaces and token definition files. Known-token hardcoding means a real surface used a token hex directly instead of a token name; unknown hardcoding means the color is not in the shared token set.

| Provider | Total | Allowed | Known token hardcoded | Unknown hardcoded | Classified unknown | Unclassified unknown |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
${usageRows}

${unknownSections}
`;
}

function tokenAudit(flags) {
  assertReactSourceReady("token-audit");
  const androidRepoDir = path.resolve(flags["android-repo-dir"] || process.env.SLOCK_ANDROID_REPO_DIR || defaultAndroidRepoDir);
  const kmpTokenFile = path.join(androidRepoDir, flags["kmp-token-path"] || defaultKmpTokenPath);
  const sharedTokens = readJson(sharedTokensPath);
  const sharedColors = sharedTokens.colors || {};
  const classifications = fs.existsSync(tokenAuditClassificationPath) ? readJson(tokenAuditClassificationPath) : {};
  const kmpColors = parseKmpSlockColors(kmpTokenFile);
  const results = Object.entries(sharedColors).map(([webToken, webValue]) => {
    const kmpName = tokenKmpColorMap[webToken];
    const webHex = normalizeHex(webValue);
    const kmpHex = kmpColors[kmpName];
    const status = !kmpName ? "missing-kmp-mapping" : !kmpHex ? "missing-kmp-token" : webHex === kmpHex ? "match" : "mismatch";
    return { webToken, webHex, kmpName, kmpHex, status };
  });
  const sourceOfTruthErrors = results.filter((item) => item.status !== "match");
  const reactFiles = walk(srcRoot, (file) => /\.(tsx|ts|css|json)$/.test(file) && !file.includes(`${path.sep}node_modules${path.sep}`));
  const kmpRoot = path.join(androidRepoDir, "compose", "shared", "src", "commonMain", "kotlin", "ai", "slock", "compose");
  const kmpFiles = walk(kmpRoot, (file) => /\.kt$/.test(file));
  const reactUsage = scanHardcodedColorUsage({
    root: reactRepoRoot,
    files: reactFiles,
    allowedFiles: tokenAuditAllowedReactFiles,
    sharedColors,
    label: "react",
  });
  const kmpUsage = scanHardcodedColorUsage({
    root: androidRepoDir,
    files: kmpFiles,
    allowedFiles: tokenAuditAllowedKmpFiles,
    sharedColors,
    label: "kmp",
  });
  const audit = {
    version: 1,
    generatedAt: new Date().toISOString(),
    inputs: {
      sharedTokens: path.relative(repoRoot, sharedTokensPath),
      reactRepoDir: reactRepoRoot,
      androidRepoDir,
      kmpTokenFile: path.relative(androidRepoDir, kmpTokenFile),
    },
    sourceOfTruth: {
      status: sourceOfTruthErrors.length === 0 ? "pass" : "fail",
      mapping: tokenKmpColorMap,
      results,
      errors: sourceOfTruthErrors,
    },
    classifications: {
      path: path.relative(repoRoot, tokenAuditClassificationPath),
      version: classifications.version || 0,
    },
    usage: {
      react: summarizeTokenUsage(reactUsage, classifications),
      kmp: summarizeTokenUsage(kmpUsage, classifications),
    },
    usageSamples: {
      reactUnknown: reactUsage.filter((item) => !item.allowedFile && item.tokenNames.length === 0).slice(0, 50),
      kmpUnknown: kmpUsage.filter((item) => !item.allowedFile && item.tokenNames.length === 0).slice(0, 50),
      reactUnclassifiedUnknown: reactUsage.filter((item) => !item.allowedFile && item.tokenNames.length === 0 && !tokenAuditClassificationFor(item, classifications)).slice(0, 50),
      kmpUnclassifiedUnknown: kmpUsage.filter((item) => !item.allowedFile && item.tokenNames.length === 0 && !tokenAuditClassificationFor(item, classifications)).slice(0, 50),
      reactKnownTokenHardcoded: reactUsage.filter((item) => !item.allowedFile && item.tokenNames.length > 0).slice(0, 50),
      kmpKnownTokenHardcoded: kmpUsage.filter((item) => !item.allowedFile && item.tokenNames.length > 0).slice(0, 50),
    },
  };
  const jsonPath = path.join(artifactRoot, "token-audit.json");
  const markdownPath = path.join(artifactRoot, "token-audit.md");
  writeJson(jsonPath, audit);
  fs.writeFileSync(markdownPath, renderTokenAuditMarkdown(audit));
  console.log(`token-audit: source ${audit.sourceOfTruth.status}; react unknown ${audit.usage.react.unknownHardcoded}; kmp unknown ${audit.usage.kmp.unknownHardcoded}`);
  console.log(`token-audit: wrote ${path.relative(repoRoot, jsonPath)} and ${path.relative(repoRoot, markdownPath)}`);
  if (sourceOfTruthErrors.length > 0 && !flags["allow-token-drift"]) {
    for (const error of sourceOfTruthErrors) {
      console.error(`token-audit: ${error.webToken} ${error.webHex} != ${error.kmpName || "unmapped"} ${error.kmpHex || "missing"}`);
    }
    throw new Error(`Token source-of-truth audit failed with ${sourceOfTruthErrors.length} error(s)`);
  }
  if (flags["strict-usage"]) {
    const usageBudget = classifications?.gate?.usageBudget || {};
    const failures = [];
    for (const provider of ["react", "kmp"]) {
      const summary = audit.usage[provider];
      const budget = usageBudget[provider] || {};
      const maxKnown = budget.maxKnownTokenHardcoded ?? 0;
      const maxUnclassifiedUnknown = budget.maxUnclassifiedUnknownHardcoded ?? 0;
      if (summary.knownTokenHardcoded > maxKnown) {
        failures.push(`${provider} known-token hardcode ${summary.knownTokenHardcoded} > ${maxKnown}`);
      }
      if (summary.unclassifiedUnknownHardcoded > maxUnclassifiedUnknown) {
        failures.push(`${provider} unclassified unknown hardcode ${summary.unclassifiedUnknownHardcoded} > ${maxUnclassifiedUnknown}`);
      }
    }
    if (failures.length > 0) {
      failures.forEach((failure) => console.error(`token-audit: strict usage failed: ${failure}`));
      throw new Error(`Token usage audit failed with ${failures.length} error(s)`);
    }
  }
}

function usage() {
  console.log(`Usage:
  slock-visual scan [--react-repo-dir third_party/slock-source]
  slock-visual validate [--manifest artifacts/visual-testing/visual-testing-cases.json|shared]
  slock-visual token-audit [--react-repo-dir third_party/slock-source] [--android-repo-dir ~/AndroidStudioProjects/Slock] [--allow-token-drift] [--strict-usage]
  slock-visual capture --providers react
  slock-visual capture --providers android [--case case.id] --command './capture-provider.sh'
  slock-visual diff [--baseline react] [--current android] [--case case.id] [--category 'app/home/*'] [--include-pending]
  slock-visual diff [--baseline react] [--current android] [--case case.id] [--include-skipped]
  slock-visual analyze [--baseline react] [--current android] [--model MODEL] [--force] [--skip-model]
  slock-visual report [--baseline react] [--current android] [--skip-analysis]
  slock-visual site [--baseline react] [--current android] [--skip-analysis] [--site-dir artifacts/visual-testing-site]
  slock-visual publish-gh-pages [--baseline react] [--current android] [--repo-dir ../slock-android] [--remote origin] [--branch gh-pages] [--subdir visual-testing] [--dry-run] [--reset-runs] [--skip-analysis] [--allow-partial-publish]
  slock-visual all
  slock-visual clean

External provider env:
  SLOCK_VISUAL_PROVIDER
  SLOCK_VISUAL_CASE_ID
  SLOCK_VISUAL_VARIANT_ID
  SLOCK_VISUAL_CASE_JSON
  SLOCK_VISUAL_RESULT_DIR
`);
}

function main() {
  const { command, flags } = parseArgs(process.argv);
  switch (command) {
    case "scan":
      scanReact();
      break;
    case "validate":
      validate(flags);
      break;
    case "token-audit":
      tokenAudit(flags);
      break;
    case "capture":
      capture(flags);
      break;
    case "diff":
      diff(flags);
      break;
    case "analyze":
      analyze(flags);
      break;
    case "report":
      report(flags);
      break;
    case "site":
      site(flags);
      break;
    case "publish-gh-pages":
      publishGhPages(flags);
      break;
    case "all":
      scanReact();
      validate(flags);
      capture(flags);
      diff(flags);
      report(flags);
      break;
    case "clean":
      clean();
      break;
    case "help":
    case "--help":
    case "-h":
      usage();
      break;
    default:
      usage();
      throw new Error(`Unknown command: ${command}`);
  }
}

try {
  main();
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`visual-testing: ${message}`);
  process.exitCode = 1;
}
