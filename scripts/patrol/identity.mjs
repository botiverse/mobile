// Stable identities for patrol issues. Fingerprints describe the current
// evidence; they are not issue identities. A changed screenshot/snippet must
// update or keep the existing open issue instead of creating a second debt.

const LEGACY_HASH_SUFFIX_RE = /#[0-9a-f]{16}$/i;

export function stableLensKey(key) {
  return String(key || "").replace(LEGACY_HASH_SUFFIX_RE, "");
}

export function hasOpenVisualIdentity(markers, caseId, comparison) {
  return markers.some((m) => m.caseId === caseId && m.comparison === comparison);
}

export function hasOpenLensIdentity(markers, lens, key) {
  const stableKey = stableLensKey(key);
  return markers.some((m) => m.lens === lens && stableLensKey(m.key) === stableKey);
}
