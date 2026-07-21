const FOREIGN_ANDROID_SYSTEM_ANR = [
  { code: "pixel_launcher_anr", pattern: /\bPixel Launcher\s+(?:isn't|is not)\s+responding\b/i },
  { code: "launcher3_anr", pattern: /\bLauncher3\s+(?:isn't|is not)\s+responding\b/i },
  { code: "system_ui_anr", pattern: /\bSystem UI\s+(?:isn't|is not)\s+responding\b/i },
  { code: "android_system_anr", pattern: /\b(?:Android System|Process system)\s+(?:isn't|is not)\s+responding\b/i },
];

// Only classify known foreign system surfaces. A generic "isn't responding"
// dialog, or an ANR naming the app under test, remains a product failure.
export function detectForeignAndroidSystemBlocker(nodes) {
  const visible = (Array.isArray(nodes) ? nodes : [])
    .flatMap((node) => [node?.text, node?.desc])
    .filter(Boolean)
    .map(String)
    .join("\n");
  for (const candidate of FOREIGN_ANDROID_SYSTEM_ANR) {
    const match = candidate.pattern.exec(visible);
    if (match) return { code: candidate.code, label: match[0] };
  }
  return null;
}
