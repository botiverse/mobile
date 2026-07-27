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
export function findComposerImageTarget(dump) {
  const nodes = parseDumpNodes(dump);
  const composers = nodes.filter((node) => node.label.trim() === "Message #all");
  if (composers.length !== 1) {
    throw new Error(`expected exactly one Message #all composer, found ${composers.length}`);
  }

  const composer = composers[0];
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

// A REAL PhotosUI picker is proven by a COMBINATION of chrome + grid cells —
// never by a lone generic word like "select" (task #26 review). Two versioned
// variants, because iOS 26 replaced the picker chrome:
//
//   ios26:  iOS 26 multi-select picker — exact "Cancel" + exact "Done" +
//           "Photos"/"Collections" tabs + exact "Select Photos" + at least one
//           INDEPENDENT "/^Photo, /" near-square cell. The aggregate container
//           (one label concatenating many "Photo, <date>" assets across the
//           full grid width) is explicitly excluded by both its multi-asset
//           label and the cell size bounds.
//   legacy: pre-iOS-26 picker — exact picker header ("Recents"/"All Photos")
//           + Cancel/Select chrome + at least one plausible near-square tile
//           below the header (header-gated).
//
// The system permission marker must never be used as picker evidence (it only
// proves the ALERT).
export function hasRealPhotoPicker(dump) {
  return classifyPhotoPicker(parseDumpNodes(dump)) !== null;
}

function classifyPhotoPicker(nodes) {
  if (nodes.length === 0) return null;
  if (hasIos26PickerChrome(nodes)) {
    const cells = independentPhotoCells(nodes);
    if (cells.length > 0) return { variant: "ios26", cells };
  }

  const hasExact = (re) => nodes.some((node) => re.test(node.label.trim()));
  const header = nodes.find((node) => /^(recents|all photos)$/i.test(node.label.trim()));
  if (header && hasExact(/^(cancel|select)$/i)) {
    const tiles = nodes.filter((node) => isPlausiblePhotoTile(node, header));
    if (tiles.length > 0) return { variant: "legacy", header, cells: tiles };
  }
  return null;
}

// iOS 26 picker chrome must be proven by INDEPENDENT SMALL NODES, never by a
// same-named fullscreen/aggregate ancestor (task #26 static review): exact
// Cancel and Done are small TOP nodes; exact Photos + Collections are a small
// same-row tab pair of similar height (the fullscreen "Photos" window node
// does NOT count); exact Select Photos is a small BOTTOM node.
function chromeGeometry(nodes) {
  const maxW = Math.max(...nodes.map((n) => n.x2));
  const maxH = Math.max(...nodes.map((n) => n.y2));
  const small = (n) => (n.x2 - n.x1) <= maxW * 0.5 && (n.y2 - n.y1) <= maxH * 0.25;
  return {
    smallTop: (n) => small(n) && n.y2 <= maxH * 0.25,
    smallBottom: (n) => small(n) && n.y1 >= maxH * 0.75,
  };
}

function exactNodes(nodes, re) {
  return nodes.filter((n) => re.test(String(n.label ?? "").trim()));
}

// Shared top chrome: exact Cancel as a small top node + the Photos/
// Collections small same-row tab pair (used by both the unselected and the
// selected iOS 26 picker contracts).
function hasIos26TopChrome(nodes, geo) {
  if (!exactNodes(nodes, /^cancel$/i).some(geo.smallTop)) return false;
  const photosTabs = exactNodes(nodes, /^photos$/i).filter(geo.smallTop);
  const collectionsTabs = exactNodes(nodes, /^collections$/i).filter(geo.smallTop);
  return photosTabs.some((p) => collectionsTabs.some((c) => (
    Math.abs(p.y1 - c.y1) <= 24
    && Math.abs((p.y2 - p.y1) - (c.y2 - c.y1)) <= 24
  )));
}

function hasIos26PickerChrome(nodes) {
  const geo = chromeGeometry(nodes);
  if (!hasIos26TopChrome(nodes, geo)) return false;
  if (!exactNodes(nodes, /^done$/i).some(geo.smallTop)) return false;
  return exactNodes(nodes, /^select photos$/i).some(geo.smallBottom);
}

// iOS 26 per-asset cells carry a single "Photo, <date>" label. The aggregate
// container's label concatenates many assets (", Photo, " inside the label)
// and spans the full grid width — both traits exclude it here.
function independentPhotoCells(nodes) {
  return nodes.filter((node) => {
    const label = node.label.trim();
    if (!/^photo, /i.test(label)) return false;
    if (/, photo, /i.test(label)) return false;
    return isNearSquareCell(node);
  });
}

function isNearSquareCell(node) {
  const w = node.x2 - node.x1;
  const h = node.y2 - node.y1;
  if (w < 60 || h < 60 || w > 400 || h > 400) return false;
  const aspect = w / h;
  return aspect >= 0.7 && aspect <= 1.4;             // photo tiles are near-square
}

function isPlausiblePhotoTile(node, header) {
  if (node.y1 < header.y2) return false;             // grid lives below the header
  return isNearSquareCell(node);
}

// Locate the first photo cell in a REAL PhotosUI picker dump (task #26). The
// whole picker variant must be proven first (no whole-dump fallback); the
// staged fixture is the most recent photo, i.e. the top-left cell of the grid
// by (y, x). Ambiguity fails closed: the top-left candidate must not conflict
// with ANY other candidate — duplicate nodes (identical bounds) and any
// positive-area bounds overlap both reject the target.
export function findPhotoCellTarget(dump) {
  const picker = classifyPhotoPicker(parseDumpNodes(dump));
  if (!picker) {
    throw new Error("no proven PhotosUI picker variant — need iOS 26 chrome (exact Cancel/Done + Photos/Collections tabs + Select Photos + independent Photo cells) or legacy picker header (Recents/All Photos) + chrome + tiles");
  }

  const tiles = [...picker.cells].sort((a, b) => (a.y1 - b.y1) || (a.x1 - b.x1));
  const first = tiles[0];
  const conflicting = tiles.filter((t) => t !== first && cellsConflict(first, t));
  if (conflicting.length) {
    throw new Error(`ambiguous top-left photo tile: ${conflicting.length + 1} duplicate/overlapping candidates`);
  }
  return {
    x: first.x,
    y: first.y,
    label: first.label,
    bounds: [first.x1, first.y1, first.x2, first.y2],
    line: first.line,
    variant: picker.variant,
  };
}

// Two candidate cells conflict when their rectangles intersect with positive
// area (task #26 static review: real bounds intersection, not a center-
// distance approximation). Duplicate nodes with identical bounds are caught
// by the same test; adjacent grid tiles share at most an edge — zero area,
// never a conflict.
function cellsConflict(a, b) {
  return Math.max(a.x1, b.x1) < Math.min(a.x2, b.x2) && Math.max(a.y1, b.y1) < Math.min(a.y2, b.y2);
}

// Composer preview proof: a NEW plausible node inside the composer rectangle
// FROZEN FROM THE BEFORE DUMP (task #26 review: the rect must never widen —
// a missing composer in the after dump is not a free pass). The before dump
// must contain exactly one composer or this returns false. The composer node
// itself never counts, and a candidate must have preview-plausible size.
export function composerHasNewPreview(beforeDump, afterDump) {
  const beforeComposers = parseDumpNodes(beforeDump).filter((node) => node.label.trim() === "Message #all");
  if (beforeComposers.length !== 1) return false;
  const rect = beforeComposers[0];
  const beforeInside = new Set(insideRect(parseDumpNodes(beforeDump), rect)
    .filter((node) => node.label.trim() !== "Message #all")
    .map((n) => `${n.label}@${n.x},${n.y}`));
  return insideRect(parseDumpNodes(afterDump), rect).some((node) => {
    if (node.label.trim() === "Message #all") return false;
    const w = node.x2 - node.x1;
    const h = node.y2 - node.y1;
    if (w < 40 || h < 40) return false; // a preview is a substantial element
    return !beforeInside.has(`${node.label}@${node.x},${node.y}`);
  });
}

function insideRect(nodes, rect) {
  return nodes.filter((node) => node.x1 >= rect.x1 && node.x2 <= rect.x2 && node.y1 >= rect.y1 && node.y2 <= rect.y2);
}

// Offline mirror of the Swift system_alert_allow_photos_full_access contract
// (task #26): given the facts gathered about the SpringBoard Photos alert,
// decide allow vs fail-closed. The loop enforces the same rules natively;
// these fixtures keep the contract executable in CI.
export function photosFullAccessAlertDecision({ alertCount, alertTitle = "", allowFullAccessCount = 0, alertDismissedAfterTap = true } = {}) {
  if (alertCount !== 1) return { ok: false, reason: `expected exactly one SpringBoard alert, found ${alertCount}` };
  if (!/photo library/i.test(String(alertTitle))) return { ok: false, reason: `not a Photos permission alert (title: ${alertTitle})` };
  if (allowFullAccessCount !== 1) return { ok: false, reason: `expected exactly one 'Allow Full Access' button, found ${allowFullAccessCount}` };
  if (!alertDismissedAfterTap) return { ok: false, reason: "Photos alert still present after tapping Allow Full Access" };
  return { ok: true };
}

// Readback proof by before/after diff: count large image-ish nodes in the
// thread area ABOVE the composer before send and after send; the new image
// message must increase that count (task #26 review: any historical large
// node must not satisfy readback).
export function countThreadImageNodes(dump) {
  const nodes = parseDumpNodes(dump);
  const composer = nodes.find((node) => node.label.trim() === "Message #all");
  if (!composer) return 0;
  return nodes.filter((node) => {
    const w = node.x2 - node.x1;
    const h = node.y2 - node.y1;
    return node.y2 < composer.y1 && w > 150 && h > 150;
  }).length;
}

// Structured selection-state diff for the cell-sampling head (task #26 static
// review). Identity is signature-keyed — identifier+type+bounds when an
// identifier exists (label is then a STATE field, so label flips stay
// detectable), else type+label+bounds for anonymous nodes. A whole-line Set
// diff cannot see "same node, enabled flipped" or "same cell, selected
// flipped"; and repeated signatures are NEVER merged last-wins (XCUI trees
// can repeat identifiers) — duplicates land in ambiguousBefore/
// ambiguousAfter and yield no state conclusion at all.
export function uiNodeSignature(node) {
  const bounds = Array.isArray(node?.bounds) ? node.bounds.join(",") : "";
  const type = node?.type ?? "?";
  const id = String(node?.identifier ?? "").trim();
  if (id) return `id:${id}|${type}|${bounds}`;
  return `anon:${type}|${node?.label ?? ""}|${bounds}`;
}

export function diffUiNodeStates(beforeNodes, afterNodes) {
  const index = (list) => {
    const map = new Map();
    for (const node of list || []) {
      const signature = uiNodeSignature(node);
      if (!map.has(signature)) map.set(signature, []);
      map.get(signature).push(node);
    }
    return map;
  };
  const before = index(beforeNodes);
  const after = index(afterNodes);
  const ambiguousBefore = [...before].filter(([, nodes]) => nodes.length > 1).map(([signature]) => signature);
  const ambiguousAfter = [...after].filter(([, nodes]) => nodes.length > 1).map(([signature]) => signature);
  const ambiguous = new Set([...ambiguousBefore, ...ambiguousAfter]);

  const added = [];
  const removed = [];
  const stateChanged = [];
  for (const [signature, nodes] of after) {
    if (ambiguous.has(signature)) continue; // no state conclusion on ambiguous identity
    const prevList = before.get(signature);
    if (!prevList) { added.push(signature); continue; }
    const prev = prevList[0];
    const node = nodes[0];
    const changed = ["label", "value", "enabled", "selected"]
      .filter((field) => (prev[field] ?? null) !== (node[field] ?? null));
    if (changed.length) {
      stateChanged.push({
        signature,
        changed,
        before: pickFields(prev, changed),
        after: pickFields(node, changed),
      });
    }
  }
  for (const signature of before.keys()) {
    if (ambiguous.has(signature)) continue;
    if (!after.has(signature)) removed.push(signature);
  }
  return { added, removed, stateChanged, ambiguousBefore, ambiguousAfter };
}

function pickFields(node, fields) {
  const out = {};
  for (const field of fields) out[field] = node[field] ?? null;
  return out;
}

// Bind the matcher's tapped cell to the STRUCTURED snapshot by exact
// label+bounds (task #26 static review): the smallest node containing the
// tap point must never impersonate the target cell — a nested image or
// checkmark child may sit inside it. The caller enforces uniqueness;
// anything other than exactly one candidate is a precondition failure.
export function exactCellCandidates(nodes, target) {
  const label = String(target?.label ?? "").trim();
  const bounds = Array.isArray(target?.bounds) ? target.bounds : [];
  return (nodes || []).filter((node) => (
    String(node?.label ?? "").trim() === label
    && Array.isArray(node?.bounds)
    && node.bounds.length === 4
    && bounds.length === 4
    && node.bounds.every((v, i) => v === bounds[i])
  ));
}

// After-tap target tracking for the cell-sampling head (task #26 static
// review): a SINGLE filter over the raw after-nodes — never a signature-
// keyed Map, which would fold fully duplicated nodes back into one and
// smuggle a last-wins pick past the ambiguity gate. A node qualifies by
// matching the target's exact before-signature OR its exact bounds (the
// union covers an anonymous-cell label flip). Each raw node is counted once
// by construction; duplicated nodes survive as length 2, i.e. an explicit
// ambiguity for the caller.
export function targetTrackingCandidates(afterNodes, targetNode) {
  const signature = uiNodeSignature(targetNode);
  const bounds = Array.isArray(targetNode?.bounds) ? targetNode.bounds : [];
  return (afterNodes || []).filter((node) => (
    uiNodeSignature(node) === signature
    || (Array.isArray(node?.bounds) && node.bounds.length === 4
      && bounds.length === 4 && node.bounds.every((v, i) => v === bounds[i]))
  ));
}

// Geometry view of a STRUCTURED driver node (describeUiNodes output, bounds
// px [x1,y1,x2,y2]) so the picker chrome predicates work on structured
// snapshots, not only on parsed text dumps.
function structuredGeom(node) {
  const b = Array.isArray(node?.bounds) ? node.bounds : [0, 0, 0, 0];
  return { label: String(node?.label ?? ""), x1: b[0], y1: b[1], x2: b[2], y2: b[3] };
}

// iOS 26 SELECTED picker contract (task #26, frozen from run 29662453310
// structured evidence — defined on STRUCTURED nodes, not the text matcher):
//   - the original small-top chrome (exact Cancel + Photos/Collections
//     same-row tab pair);
//   - EXACTLY ONE independent single-asset Photo image with selected=true
//     (near-square, label starts "Photo, ", not the multi-asset aggregate —
//     the active "Photos" TAB also reads selected=true but is a button, so
//     it can never satisfy this);
//   - EXACTLY ONE enabled small-top button {identifier:Add, label:Done};
//   - EXACTLY ONE small-bottom exact "1 Photo" button.
// Any duplicate/extra selected cell, duplicate/disabled/misplaced Done, or
// missing bottom state fails closed (returns null).
export function classifySelectedPhotoPicker(nodes) {
  const list = (nodes || []).filter((n) => Array.isArray(n?.bounds) && n.bounds.length === 4);
  if (!list.length) return null;
  const geomNodes = list.map(structuredGeom);
  const geo = chromeGeometry(geomNodes);
  if (!hasIos26TopChrome(geomNodes, geo)) return null;

  const selectedCells = list.filter((n) => (
    n.selected === true
    && n.type === "image"
    && /^photo, /i.test(String(n.label ?? "").trim())
    && !/, photo, /i.test(String(n.label ?? "").trim())
    && isNearSquareCell(structuredGeom(n))
  ));
  if (selectedCells.length !== 1) return null;

  const doneButtons = list.filter((n) => (
    String(n.identifier ?? "").trim() === "Add"
    && String(n.label ?? "").trim() === "Done"
    && n.type === "button"
    && n.enabled === true
    && geo.smallTop(structuredGeom(n))
  ));
  if (doneButtons.length !== 1) return null;

  const onePhoto = list.filter((n) => (
    String(n.label ?? "").trim() === "1 Photo"
    && n.type === "button"
    && geo.smallBottom(structuredGeom(n))
  ));
  if (onePhoto.length !== 1) return null;

  return { variant: "ios26-selected", selectedCell: selectedCells[0], doneNode: doneButtons[0] };
}

// The unique Done tap target of a PROVEN ios26-selected picker (task #26
// static review: no tapText, no fuzzy match, no coordinate constants — the
// target comes only from the frozen contract above).
export function findSelectedPhotoDoneTarget(nodes) {
  const picker = classifySelectedPhotoPicker(nodes);
  if (!picker) {
    throw new Error("no proven ios26-selected picker — need Cancel + tab pair + exactly one selected Photo image + exactly one enabled Add/Done top button + bottom 1 Photo button");
  }
  const b = picker.doneNode.bounds;
  return {
    x: Math.round((b[0] + b[2]) / 2),
    y: Math.round((b[1] + b[3]) / 2),
    label: picker.doneNode.label,
    identifier: picker.doneNode.identifier,
    bounds: [...b],
  };
}

// Raft image review/editor contract discovered by task #26 run 30294565343.
// This contract is STRUCTURED and intentionally stricter than merely seeing
// an image plus a Done label:
//   - one Raft full-screen root and one enabled unlabeled top-left back button;
//   - exactly one non-fullscreen, large review image;
//   - exactly one top filename matching that image label and one adjacent
//     size metadata node directly below it;
//   - one enabled Edit and one enabled Done in the bottom action row, with
//     Edit left of Done.
// Fullscreen/aggregate image ancestors are excluded by the review-frame
// geometry. Zero or multiple real image candidates, metadata, or actions
// fail closed.
export function classifyImageReviewEditor(nodes) {
  const list = (nodes || []).filter((n) => Array.isArray(n?.bounds) && n.bounds.length === 4);
  if (!list.length) return null;
  const geoms = list.map(structuredGeom);
  const maxW = Math.max(...geoms.map((n) => n.x2));
  const maxH = Math.max(...geoms.map((n) => n.y2));
  if (!(maxW > 0 && maxH > 0)) return null;

  const width = (n) => n.bounds[2] - n.bounds[0];
  const height = (n) => n.bounds[3] - n.bounds[1];
  const top = (n) => n.bounds[3] <= maxH * 0.25;
  const bottom = (n) => n.bounds[1] >= maxH * 0.75;

  const roots = list.filter((n) => (
    n.type === "other"
    && String(n.label ?? "").trim() === "Raft"
    && width(n) >= maxW * 0.9
    && height(n) >= maxH * 0.9
  ));
  if (roots.length !== 1) return null;

  const backButtons = list.filter((n) => (
    n.type === "button"
    && String(n.label ?? "").trim() === ""
    && n.enabled === true
    && top(n)
    && n.bounds[2] <= maxW * 0.25
    && width(n) <= maxW * 0.25
  ));
  if (backButtons.length !== 1) return null;

  const images = list.filter((n) => (
    n.type === "image"
    && String(n.label ?? "").trim() !== ""
    && width(n) >= maxW * 0.7
    && height(n) >= maxH * 0.4
    // Exclude fullscreen/aggregate ancestors: the real review image is
    // inset below the header and above the bottom action band.
    && n.bounds[1] >= maxH * 0.1
    && n.bounds[3] <= maxH * 0.9
    && n.bounds[0] <= maxW * 0.1
    && n.bounds[2] >= maxW * 0.9
  ));
  if (images.length !== 1) return null;
  const imageNode = images[0];

  const filenameNodes = list.filter((n) => (
    n.type === "staticText"
    && String(n.label ?? "").trim() === String(imageNode.label).trim()
    && top(n)
    && n.bounds[0] > backButtons[0].bounds[0]
    && n.bounds[3] <= imageNode.bounds[1]
  ));
  if (filenameNodes.length !== 1) return null;
  const filenameNode = filenameNodes[0];

  const sizeNodes = list.filter((n) => (
    n.type === "staticText"
    && /^\d+(?:\.\d+)?\s*(?:B|KB|MB|GB)$/i.test(String(n.label ?? "").trim())
    && top(n)
    && n.bounds[0] === filenameNode.bounds[0]
    && n.bounds[1] >= filenameNode.bounds[3]
    && n.bounds[3] <= imageNode.bounds[1]
  ));
  if (sizeNodes.length !== 1) return null;

  const editButtons = list.filter((n) => (
    n.type === "button"
    && String(n.label ?? "").trim() === "Edit"
    && n.enabled === true
    && bottom(n)
  ));
  const doneButtons = list.filter((n) => (
    n.type === "button"
    && String(n.label ?? "").trim() === "Done"
    && n.enabled === true
    && bottom(n)
  ));
  if (editButtons.length !== 1 || doneButtons.length !== 1) return null;
  const editNode = editButtons[0];
  const doneNode = doneButtons[0];
  if (
    Math.abs(editNode.bounds[1] - doneNode.bounds[1]) > 24
    || Math.abs(height(editNode) - height(doneNode)) > 24
    || editNode.bounds[2] >= doneNode.bounds[0]
    || imageNode.bounds[3] >= Math.min(editNode.bounds[1], doneNode.bounds[1])
  ) return null;

  return {
    variant: "raft-image-review-editor",
    rootNode: roots[0],
    backNode: backButtons[0],
    imageNode,
    filenameNode,
    sizeNode: sizeNodes[0],
    editNode,
    doneNode,
  };
}

// Unique second-Done target from a PROVEN Raft image review/editor page.
// No tapText, fuzzy label match, or coordinate constant is permitted.
export function findImageReviewDoneTarget(nodes) {
  const review = classifyImageReviewEditor(nodes);
  if (!review) {
    throw new Error("no proven Raft image review/editor — need unique root/back/large image/matching filename+size/Enabled Edit+Done bottom row");
  }
  const b = review.doneNode.bounds;
  return {
    x: Math.round((b[0] + b[2]) / 2),
    y: Math.round((b[1] + b[3]) / 2),
    label: review.doneNode.label,
    identifier: review.doneNode.identifier,
    type: review.doneNode.type,
    enabled: review.doneNode.enabled,
    bounds: [...b],
  };
}

// Composer attachment-tile contract discovered after the review/editor Done
// in task #26 run 30297014531. This is intentionally keyed by the exact image
// filename carried by the proven review/editor page; a historical message
// attachment elsewhere in the channel must never impersonate the composer
// tile. The frozen failure shape is:
//   - one Raft root and one enabled Message #all composer;
//   - exactly one enabled square-ish filename button immediately above it;
//   - exactly one small enabled unlabeled close button adjacent to the tile's
//     top-right corner;
//   - for terminal failure, an enabled button with the exact combined label
//     "Upload failed, Tap to retry" on the exact same bounds as the filename
//     button.
// The source contract also exposes an Uploading/progress text overlay, which
// is reported as uploading when it is uniquely contained by the tile. Absence
// of either frozen status is deliberately `unproven`, NEVER success: a real
// successful iOS node tree plus upload/readback authority must be frozen before
// this matcher may learn a success state.
export function classifyComposerAttachmentTile(nodes, { expectedFileName = "" } = {}) {
  const expected = String(expectedFileName ?? "").trim();
  if (!expected) return null;
  const list = (nodes || []).filter((n) => Array.isArray(n?.bounds) && n.bounds.length === 4);
  if (!list.length) return null;

  const maxW = Math.max(...list.map((n) => n.bounds[2]));
  const maxH = Math.max(...list.map((n) => n.bounds[3]));
  if (!(maxW > 0 && maxH > 0)) return null;
  const width = (n) => n.bounds[2] - n.bounds[0];
  const height = (n) => n.bounds[3] - n.bounds[1];
  const sameBounds = (a, b) => a.bounds.every((value, index) => value === b.bounds[index]);
  const containedBy = (inner, outer) => (
    inner.bounds[0] >= outer.bounds[0]
    && inner.bounds[1] >= outer.bounds[1]
    && inner.bounds[2] <= outer.bounds[2]
    && inner.bounds[3] <= outer.bounds[3]
  );

  const roots = list.filter((n) => (
    n.type === "other"
    && String(n.label ?? "").trim() === "Raft"
    && width(n) >= maxW * 0.9
    && height(n) >= maxH * 0.9
  ));
  if (roots.length !== 1) return null;

  const composers = list.filter((n) => (
    n.type === "button"
    && String(n.label ?? "").trim() === "Message #all"
    && n.enabled === true
    && width(n) >= maxW * 0.7
    && n.bounds[1] >= maxH * 0.65
    && n.bounds[3] <= maxH
  ));
  if (composers.length !== 1) return null;
  const composerNode = composers[0];

  const filenameButtons = list.filter((n) => {
    if (
      n.type !== "button"
      || String(n.label ?? "").trim() !== expected
      || n.enabled !== true
      || n.bounds[3] > composerNode.bounds[1] + 8
      || n.bounds[1] < maxH * 0.5
    ) return false;
    const w = width(n);
    const h = height(n);
    return w >= maxW * 0.08
      && w <= maxW * 0.35
      && h >= maxH * 0.05
      && h <= maxH * 0.2
      && w / h >= 0.6
      && w / h <= 1.4;
  });
  if (filenameButtons.length !== 1) return null;
  const filenameNode = filenameButtons[0];
  const tileW = width(filenameNode);
  const tileH = height(filenameNode);

  const closeButtons = list.filter((n) => (
    n.type === "button"
    && String(n.label ?? "").trim() === ""
    && n.enabled === true
    && width(n) > 0
    && height(n) > 0
    && width(n) <= tileW * 0.5
    && height(n) <= tileH * 0.5
    && n.bounds[0] >= filenameNode.bounds[0] + tileW * 0.5
    && n.bounds[0] <= filenameNode.bounds[2]
    && n.bounds[2] <= filenameNode.bounds[2] + tileW * 0.2
    && n.bounds[1] >= filenameNode.bounds[1] - tileH * 0.2
    && n.bounds[1] <= filenameNode.bounds[1] + tileH * 0.3
  ));
  if (closeButtons.length !== 1) return null;

  const failureLabelButtons = list.filter((n) => (
    n !== filenameNode
    && n.type === "button"
    && String(n.label ?? "").trim() === "Upload failed, Tap to retry"
  ));
  const failureStatusButtons = failureLabelButtons.filter((n) => (
    n.enabled === true
    && sameBounds(n, filenameNode)
  ));
  const uploadingStatusNodes = list.filter((n) => (
    n !== filenameNode
    && (n.type === "staticText" || n.type === "button")
    && (/^Uploading$/i.test(String(n.label ?? "").trim()) || /^(?:100|[1-9]?\d)%$/.test(String(n.label ?? "").trim()))
    && containedBy(n, filenameNode)
  ));
  if (failureLabelButtons.length > 0 && (failureLabelButtons.length !== 1 || failureStatusButtons.length !== 1)) return null;
  if (failureStatusButtons.length > 1 || uploadingStatusNodes.length > 1) return null;
  if (failureStatusButtons.length === 1 && uploadingStatusNodes.length === 1) return null;

  const state = failureStatusButtons.length === 1
    ? "failure"
    : uploadingStatusNodes.length === 1
      ? "uploading"
      : "unproven";
  return {
    variant: "composer-attachment-tile",
    state,
    successProven: false,
    rootNode: roots[0],
    composerNode,
    filenameNode,
    statusNode: failureStatusButtons[0] ?? uploadingStatusNodes[0] ?? null,
    closeNode: closeButtons[0],
    exactFilenameCandidateCount: filenameButtons.length,
    exactFailureStatusCandidateCount: failureStatusButtons.length,
    exactUploadingStatusCandidateCount: uploadingStatusNodes.length,
    exactCloseCandidateCount: closeButtons.length,
  };
}
