const BOUNDS_RE = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/;
const LABEL_RE = /"([^"]*)"/;

function normalizeLabel(value) {
  return String(value || "").replace(/\s/g, "").toLowerCase();
}

export function parseDumpNodes(dump) {
  return String(dump || "")
    .split("\n")
    .map((line) => {
      const label = LABEL_RE.exec(line)?.[1] ?? "";
      const match = BOUNDS_RE.exec(line);
      if (!match) return null;
      const [x1, y1, x2, y2] = match.slice(1).map(Number);
      if (![x1, y1, x2, y2].every(Number.isFinite) || x2 <= x1 || y2 <= y1) return null;
      return {
        line,
        label,
        normalizedLabel: normalizeLabel(label),
        isInput: line.includes("[INPUT]"),
        isBtn: line.includes("[BTN]"),
        x1,
        y1,
        x2,
        y2,
        x: Math.round((x1 + x2) / 2),
        y: Math.round((y1 + y2) / 2),
      };
    })
    .filter(Boolean);
}

// Dumps are useful runtime evidence, but an editable node may contain an email,
// password, token, or other typed value. Keep geometry/flags while replacing
// every editable value before the artifact leaves the runner.
export function redactEditableValues(dump) {
  return String(dump || "")
    .split("\n")
    .map((line) => {
      if (!line.includes("[INPUT]")) return line;
      const firstQuote = line.indexOf('"');
      const lastQuote = line.lastIndexOf('"');
      if (firstQuote < 0 || lastQuote <= firstQuote) return line;
      return `${line.slice(0, firstQuote)}"[REDACTED]"${line.slice(lastQuote + 1)}`;
    })
    .join("\n");
}

// The first image-button tap on a clean simulator can present the system-owned
// Photos permission alert. XCUITest's app-scoped accessibility dump does not
// include that SpringBoard alert, so accept either app dump markers (picker or
// permission UI) or the corresponding post-tap unified-log title. Keep this
// pure so the evidence parser is covered by offline fixtures.
export function hasPhotoPickerGate({ appDump = "", systemLog = "" } = {}) {
  const dumpMarkers = /photos|recents|photo library|选择|照片|允许|allow|cancel|取消|select/i;
  const systemMarkers = /(?:would like|request(?:ed)?|controllerTitle).*photo library|photo library.*(?:access|permission)/i;
  return dumpMarkers.test(String(appDump)) || systemMarkers.test(String(systemLog));
}

// Locate the #all row only inside the first channel-row band beneath the
// CHANNELS heading. iOS may expose it as one "#all" node or split the icon and
// text into adjacent "#" + "all" nodes. Exact labels and uniqueness are
// deliberate: message text containing "all" must never become a tap target.
export function findAllChannelTarget(dump, { channelBandPx = 360, rowTolerancePx = 80 } = {}) {
  const nodes = parseDumpNodes(dump);
  const headings = nodes.filter((node) => normalizeLabel(node.label).startsWith("channels"));
  if (headings.length !== 1) {
    throw new Error(`expected exactly one CHANNELS heading, found ${headings.length}`);
  }

  const heading = headings[0];
  const inChannelBand = (node) => node.y >= heading.y2 && node.y <= heading.y2 + channelBandPx;
  const direct = nodes.filter((node) => node.normalizedLabel === "#all" && inChannelBand(node));
  const splitText = nodes.filter((node) => node.normalizedLabel === "all" && inChannelBand(node));
  const candidates = [...direct];

  for (const textNode of splitText) {
    const adjacentHash = nodes.find((node) => (
      node.normalizedLabel === "#"
      && inChannelBand(node)
      && node.x2 <= textNode.x1
      && Math.abs(node.y - textNode.y) <= rowTolerancePx
    ));
    candidates.push({ ...textNode, splitHashLine: adjacentHash?.line ?? null });
  }

  if (candidates.length !== 1) {
    throw new Error(`expected exactly one #all channel target in CHANNELS band, found ${candidates.length}`);
  }

  const target = candidates[0];
  return {
    x: target.x,
    y: target.y,
    label: target.label,
    mode: target.normalizedLabel === "#all" ? "single-node" : "split-node",
    line: target.line,
    splitHashLine: target.splitHashLine ?? null,
    channelsHeadingLine: heading.line,
  };
}

// The Kuikly composer currently exposes its whole input surface as one
// "Message #all" node while the image/paperclip icons may have no independent
// accessibility nodes. Prefer a real contained button when one exists. When it
// does not, derive the first (image) square from the composer bounds rather
// than hard-coding a device coordinate. The image action is the leftmost icon
// in the composer's bottom row; the paperclip is immediately to its right.
export function findComposerNode(dump) {
  const nodes = parseDumpNodes(dump);
  const named = nodes.filter((node) => /^Message #/.test(node.label.trim()));
  if (named.length === 1) return named[0];
  if (named.length > 1) {
    throw new Error(`expected exactly one Message # composer, found ${named.length}`);
  }
  const maxY = Math.max(0, ...nodes.map((node) => node.y2));
  const inputs = nodes.filter((node) => (
    node.isInput
    && (node.y2 - node.y1) >= 120
    && (node.x2 - node.x1) >= 600
    && node.y1 >= maxY * 0.55
  ));
  if (inputs.length !== 1) {
    throw new Error(`expected exactly one bottom [INPUT] composer, found ${inputs.length}`);
  }
  return inputs[0];
}

export function dumpShowsComposer(dump) {
  try {
    findComposerNode(dump);
    return true;
  } catch {
    return false;
  }
}

export function findComposerImageTarget(dump) {
  const nodes = parseDumpNodes(dump);
  const composer = findComposerNode(dump);
  const width = composer.x2 - composer.x1;
  const height = composer.y2 - composer.y1;
  if (width < 600 || height < 120) {
    throw new Error(`Message #all composer bounds are implausible: ${composer.line}`);
  }

  const containedButtons = nodes
    .filter((node) => (
      node.line.includes("[BTN]")
      && node.x1 >= composer.x1
      && node.x2 <= composer.x2
      && node.y1 >= composer.y1
      && node.y2 <= composer.y2
      && node.x <= composer.x1 + Math.min(260, width * 0.3)
    ))
    .sort((a, b) => a.x - b.x);

  if (containedButtons.length > 0) {
    if (containedButtons.length > 1 && Math.abs(containedButtons[0].x - containedButtons[1].x) < 12) {
      throw new Error("composer image target is ambiguous: leftmost buttons overlap");
    }
    const target = containedButtons[0];
    return { x: target.x, y: target.y, mode: "semantic-button", line: target.line, composerLine: composer.line };
  }

  const inset = Math.round(Math.min(72, Math.max(54, height * 0.32)));
  const x = composer.x1 + inset;
  const y = composer.y2 - inset;
  if (x <= composer.x1 || x >= composer.x2 || y <= composer.y1 || y >= composer.y2) {
    throw new Error("composer-relative image target fell outside composer bounds");
  }
  return { x, y, mode: "composer-relative", line: null, composerLine: composer.line };
}
