// patrol/renderer_coverage.mjs — markdown renderer coverage lens (task #495).
//
// The EMAIL_AUTOLINK bug (task #493, fixed in mobile#995) was a token type the
// annotator's `when` had no branch for: the lexer emitted it, nothing appended
// it, content silently vanished. This lens statically diffs the token/element
// types DEFINED by org.jetbrains:markdown (0.7.5, pinned in shared gradle)
// against the types the annotator dispatch actually references, minus an
// allowlist of types consumed elsewhere (element-level branches, dedicated
// renderers, or the pre-parser HTML escape). Anything left is a
// silent-drop candidate issue.
//
// Refresh TYPE_UNIVERSE when the markdown dependency version changes:
//   unzip markdown-jvm-<v>.jar org/intellij/markdown/{MarkdownTokenTypes,
//   MarkdownElementTypes,flavours/gfm/GFM{Token,Element}Types}.class
//   and `strings` the constants.
//
// Usage: node scripts/patrol/renderer_coverage.mjs [--live] [--repo owner/name]
import fs from "node:fs";
import crypto from "node:crypto";
import { runPatrol } from "./lib.mjs";

const args = process.argv.slice(2);
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const LIVE = args.includes("--live");
const REPO = arg("--repo", "botiverse/mobile");

// The annotator handles inline runs; block elements are dispatched across the
// library (handleElement -> MarkdownHeader/Code/List/Table composables), so
// "handled" is collected from the whole commonMain of the fork.
const LIB_ROOT = "third_party/kuikly-compose-markdown/src/commonMain/kotlin";

// org.jetbrains:markdown 0.7.5 — token and element constants.
const TYPE_UNIVERSE = {
  "MarkdownTokenTypes": "ATX_CONTENT ATX_HEADER AUTOLINK BACKTICK BAD_CHARACTER BLOCK_QUOTE CODE_FENCE_CONTENT CODE_FENCE_END CODE_FENCE_START CODE_LINE COLON DOUBLE_QUOTE EMAIL_AUTOLINK EMPH EOL ESCAPED_BACKTICKS EXCLAMATION_MARK FENCE_LANG GT HARD_LINE_BREAK HORIZONTAL_RULE HTML_BLOCK_CONTENT HTML_TAG LBRACKET LINK_ID LINK_TITLE LIST_BULLET LIST_NUMBER LPAREN LT RBRACKET RPAREN SETEXT_1 SETEXT_2 SETEXT_CONTENT SINGLE_QUOTE TEXT URL WHITE_SPACE".split(" "),
  "MarkdownElementTypes": "ATX_1 ATX_2 ATX_3 ATX_4 ATX_5 ATX_6 AUTOLINK BLOCK_QUOTE CODE_BLOCK CODE_FENCE CODE_SPAN EMPH FULL_REFERENCE_LINK HTML_BLOCK IMAGE INLINE_LINK LINK_DEFINITION LINK_DESTINATION LINK_LABEL LINK_TITLE MARKDOWN_FILE ORDERED_LIST PARAGRAPH SETEXT_1 SETEXT_2 SHORT_REFERENCE_LINK STRONG UNORDERED_LIST LIST_ITEM".split(" "),
  "GFMTokenTypes": "CELL CHECK_BOX DOLLAR GFM_AUTOLINK TABLE_SEPARATOR TILDE".split(" "),
  "GFMElementTypes": "BLOCK_MATH HEADER INLINE_MATH ROW STRIKETHROUGH TABLE".split(" "),
};

// Consumed at other altitudes — each entry needs a justification.
const ALLOWLIST = new Set([
  // structural tokens consumed by element-level rendering
  "ATX_CONTENT", "ATX_HEADER", "SETEXT_1", "SETEXT_2", "SETEXT_CONTENT",
  "CODE_FENCE_CONTENT", "CODE_FENCE_END", "CODE_FENCE_START", "CODE_LINE", "FENCE_LANG",
  "LIST_BULLET", "LIST_NUMBER", "LINK_ID", "LINK_TITLE", "LINK_DESTINATION", "LINK_LABEL",
  "HORIZONTAL_RULE",              // dedicated horizontalRule component
  "HTML_TAG", "HTML_BLOCK_CONTENT", "HTML_BLOCK", // pre-parser escapes raw HTML to literals
  "BAD_CHARACTER",                 // lexer error token; no content contract
  "MARKDOWN_FILE", "LINK_DEFINITION", // structural containers
  "CELL", "TABLE_SEPARATOR", "ROW", "TABLE", "HEADER", // table renderer walks these directly
  "URL",                           // consumed inside link elements
  "BLOCK_MATH", "INLINE_MATH", "DOLLAR", // math unsupported by product spec (renders as text via TEXT runs)
]);

function* ktFiles(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = `${dir}/${e.name}`;
    if (e.isDirectory()) yield* ktFiles(p);
    else if (e.name.endsWith(".kt")) yield p;
  }
}
const handled = new Set();
for (const f of ktFiles(LIB_ROOT)) {
  const src = fs.readFileSync(f, "utf8");
  for (const m of src.matchAll(/(?:MarkdownTokenTypes|MarkdownElementTypes|GFMTokenTypes|GFMElementTypes)\.([A-Z_0-9]+)/g)) {
    handled.add(m[1]);
  }
}

const missing = [];
for (const [ns, names] of Object.entries(TYPE_UNIVERSE)) {
  for (const name of names) {
    if (!handled.has(name) && !ALLOWLIST.has(name)) missing.push(`${ns}.${name}`);
  }
}

const findings = missing.length === 0 ? [] : [{
  key: "renderer-coverage",
  fingerprint: crypto.createHash("sha256").update(missing.join(",")).digest("hex").slice(0, 16),
  rank: missing.length,
  title: `[renderer-coverage] ${missing.length} markdown type(s) with no annotator branch (silent-drop candidates)`,
  body: [
    "The annotator dispatch has no branch for these types and they are not on the consumed-elsewhere allowlist — content carried by them may be silently dropped (the task #493 EMAIL_AUTOLINK class):",
    "",
    ...missing.map((m) => `- \`${m}\``),
    "",
    "Triage: add a rendering branch, or move to the allowlist in `scripts/patrol/renderer_coverage.mjs` with a justification.",
  ].join("\n"),
  labels: ["code-smell", "auto-triage", "severity:high"],
}];

console.log(`[renderer-coverage] handled=${handled.size} missing=${missing.length}${missing.length ? " -> " + missing.join(", ") : ""}`);
const report = runPatrol({ repo: REPO, lens: "renderer-coverage", label: "code-smell", findings, cap: 2, live: LIVE });
console.log(JSON.stringify(report, null, 1));
