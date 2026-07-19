export function normalizeAssertionAnnotation(annotation = {}) {
  const explicit = String(
    annotation.assertion_id ?? annotation.id ?? annotation.requirement_id ?? "",
  ).trim();
  const label = String(annotation.label ?? "").trim();
  const matched = /^\[([a-z0-9][a-z0-9-]*)\](?:\s|$)/i.exec(label);
  const assertionId = explicit || matched?.[1] || "";
  return {
    ...annotation,
    ...(assertionId ? { assertion_id: assertionId } : {}),
  };
}
