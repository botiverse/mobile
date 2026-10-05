// The pinned pi-ai catalogs predate this preview. Keep its wire identity explicit
// until the SDK supplies it; never silently call M3 when the preview is selected.
// Contract: https://platform.minimax.io/docs/guides/text-generation
export const DEFAULT_PI_MODEL = "MiniMax-M3.1-Flash-Preview";

export function resolvePiModel(models, provider, name) {
  const registered = models.getModel(provider, name);
  if (registered) return registered;
  if (name === DEFAULT_PI_MODEL && ["minimax", "minimax-cn"].includes(provider)) {
    const base = models.getModel(provider, "MiniMax-M3");
    if (base) return {
      ...base,
      id: name,
      name,
      // Preview pricing is not published: zero disables SDK cost estimation;
      // it does NOT mean the service is free. Preserve token usage reporting.
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      // Thinking is mandatory; omit an explicit disabled mode in streamSimple.
      thinkingLevelMap: { ...base.thinkingLevelMap, off: null },
      compat: { ...base.compat, forceAdaptiveThinking: true },
    };
  }
  throw new Error(`Unknown pi-ai model: ${provider}/${name}`);
}
