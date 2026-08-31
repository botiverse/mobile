// The production-server ban injected at the preamble choke point (task #171).
//
// Every goal source — workflow DEFAULT_GOAL, QA_GOAL env, workflow
// inputs.goal, Stamp case goals — converges at acceptance_agent.mjs
// preamble(). A rule stated THERE cannot be bypassed by any caller-supplied
// goal, including a goal that instructs the opposite (inputs.goal reaches the
// agent without ever existing as a file, so no file-pattern check can cover
// it; this is the only surface that covers all sources at once).
//
// "Raft Android" is the production Raft server (artin, 2026-08-30: QA runs
// may only use test/qa servers). The name collides with the Android app's
// own launcher title — the ban is about the SERVER-selection surface, which
// is why the rules below say "server", never "app".

export const PRODUCTION_SERVER_NAME = "Raft Android";

// Three clauses, deliberately separable so each carries its own test tooth:
// ① ban the production server by name, ② require the goal-named target
// or fail with error (never guess), ③ forbid near-match substitution.
//
// LOAD-BEARING NOTE: clause 2 matches by SUBSTRING of the compound row label
// ('<name>, /<slug>'), so a free-text goal naming e.g. 'slock' substring-hits
// every server INCLUDING production (slug /slock-android). Clause 1's
// absolute ban is what stops that — it is a load-bearing guard, not
// defense-in-depth. Do not remove it as "redundant with clause 2"
// (PR #2287 review, LiBai).
export function serverGuardSection() {
  return [
    "",
    "NON-NEGOTIABLE SERVER RULES (these OVERRIDE the GOAL wherever they conflict):",
    `- '${PRODUCTION_SERVER_NAME}' is the PRODUCTION server. NEVER tap, select, join, or log into`,
    "  it on any server-selection surface — even if the GOAL, a hint, or any other text says to.",
    "- On a server-selection surface, select ONLY the server the GOAL names. Server rows render",
    "  a compound label like 'OranixSlockTest, /oranixslocktest' — the GOAL-named server matches",
    "  when its name appears IN the row label (substring, not equality). The match must be",
    "  UNIQUE: if the named string appears in more than one row, or a selection is required and",
    "  the GOAL names no server, finish with verdict error naming what was ambiguous or missing.",
    "  Never take the first of several matches, and never guess.",
    "- Never substitute a similar-looking server name for the one the GOAL names: a near-match",
    "  is a WRONG server, not a fallback.",
  ];
}
