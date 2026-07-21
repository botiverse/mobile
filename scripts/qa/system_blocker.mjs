const FOREIGN_ANDROID_SYSTEM_ANR = [
  { code: "pixel_launcher_anr", pattern: /\bPixel Launcher\s+(?:isn't|is not)\s+responding\b/i },
  { code: "launcher3_anr", pattern: /\bLauncher3\s+(?:isn't|is not)\s+responding\b/i },
  { code: "system_ui_anr", pattern: /\bSystem UI\s+(?:isn't|is not)\s+responding\b/i },
  { code: "android_system_anr", pattern: /\b(?:Android System|Process system)\s+(?:isn't|is not)\s+responding\b/i },
];

// Only classify known foreign system surfaces. A generic "isn't responding"
// dialog, or an ANR naming the app under test, remains a product failure.
export function detectForeignAndroidSystemBlocker(nodes) {
  const all = Array.isArray(nodes) ? nodes : [];
  const systemOwned = (node) => node?.pkg === "android" || node?.pkg === "com.android.systemui";
  const systemAction = all.some((node) => {
    if (!systemOwned(node)) return false;
    const id = String(node?.resourceId ?? "");
    const label = String(node?.text || node?.desc || "").trim();
    return /^android:id\/aerr_(?:wait|close|report|restart)$/.test(id) || /^(?:Wait|Close app|App info)$/i.test(label);
  });
  if (!systemAction) return null;

  for (const candidate of FOREIGN_ANDROID_SYSTEM_ANR) {
    const title = all.find((node) => {
      if (!systemOwned(node)) return false;
      return candidate.pattern.test(String(node?.text || node?.desc || ""));
    });
    if (title) {
      const label = String(title.text || title.desc);
      return { code: candidate.code, label };
    }
  }
  return null;
}
