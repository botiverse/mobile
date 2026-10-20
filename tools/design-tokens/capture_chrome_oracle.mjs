#!/usr/bin/env node
// Paint every generated colour token in Chromium and record the result in
// chrome-oracle.json, which `gen_tokens.py --check` compares against.
//
// Run after bumping the design sources (needs network once for Playwright):
//   d=$(mktemp -d) && npm --prefix "$d" i playwright@1.61.1 && npx --prefix "$d" playwright install chromium
//   PLAYWRIGHT_MODULE="$d/node_modules/playwright/index.js" node tools/design-tokens/capture_chrome_oracle.mjs
//
// CHROMIUM_PATH=<headless shell> reuses an already installed browser instead.
//
// Each token is painted as a solid rectangle over white and over black on a
// 2D canvas (sRGB), so translucent tokens are checked on both channels and alpha.
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");

const dump = JSON.parse(execFileSync("python3", [join(here, "gen_tokens.py"), "--dump-json"], { encoding: "utf8", maxBuffer: 1 << 26 }));
// raft-ui 0.7.1 and later: one "raft" family; the root carries `.light` or `.dark`.
const roots = {
  raftLight: { cls: "light" },
  raftDark: { cls: "dark" },
};

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage();
await page.setContent(`<!doctype html><html><head><style>${dump.css}</style></head><body><div id="probe"></div></body></html>`);
const themes = {};
for (const [name, root] of Object.entries(roots)) {
  themes[name] = await page.evaluate(({ root, tokens }) => {
    const html = document.documentElement;
    html.removeAttribute("data-theme");
    html.className = root.cls;
    const probe = document.getElementById("probe");
    const canvas = document.createElement("canvas");
    canvas.width = 2;
    canvas.height = 1;
    const ctx = canvas.getContext("2d", { colorSpace: "srgb", willReadFrequently: true });
    const out = {};
    for (const token of tokens) {
      probe.style.backgroundColor = `var(${token})`;
      const css = getComputedStyle(probe).backgroundColor;
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, 1, 1);
      ctx.fillStyle = "#000000";
      ctx.fillRect(1, 0, 1, 1);
      ctx.fillStyle = css;
      ctx.fillRect(0, 0, 2, 1);
      const px = ctx.getImageData(0, 0, 2, 1).data;
      out[token] = { css, onWhite: [px[0], px[1], px[2]], onBlack: [px[4], px[5], px[6]] };
    }
    return out;
  }, { root, tokens: dump.themes[name] });
}
const version = browser.version();
await browser.close();
writeFileSync(join(here, "chrome-oracle.json"), JSON.stringify({ chromium: version, themes }, null, 1) + "\n");
console.log(`chrome-oracle.json: ${Object.values(themes).reduce((n, t) => n + Object.keys(t).length, 0)} values from Chromium ${version}`);
