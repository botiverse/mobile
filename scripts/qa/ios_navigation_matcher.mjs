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
