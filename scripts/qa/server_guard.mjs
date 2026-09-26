// The server-selection rules injected at the preamble choke point (task #171).
//
// Every goal source — workflow DEFAULT_GOAL, QA_GOAL env, workflow
// inputs.goal, Stamp case goals — converges at acceptance_agent.mjs
// preamble(). A rule stated THERE cannot be bypassed by any caller-supplied
// goal, including a goal that instructs the opposite (inputs.goal reaches the
// agent without ever existing as a file, so no file-pattern check can cover
// it; this is the only surface that covers all sources at once).
//
// ⚠️ 2026-08-31, artin's decision: the production server is NO LONGER named or
// special-cased here. Previously a first clause banned it by name and overrode
// the GOAL. artin's line: don't special-case that name, and don't keep it in
// the prompt at all ("按我的口径哦，不用特别判断不能出现 Raft Android").
//
// KNOWN CONSEQUENCE, recorded so nobody rediscovers it as a surprise: the rules
// below match a row by SUBSTRING of the compound label ('<name>, /<slug>'). If a
// GOAL ever names a string that uniquely substring-hits the production row, these
// rules will treat that row as the goal-named server and select it — the removed
// clause was what stopped that. Measured on 2026-08-31: 0 of the 26 case files
// name such a string, so the path is currently unreachable, but that is a fact
// about today's cases, not a property of the rules. (Note: changing this wording
// from substring to equality would still be a prompt convention, not a structural
// guard — acceptance_agent.mjs has no server-matching code; only selecting the
// server row in code before the model loop or server-side ACL is structural.)

// Two clauses, deliberately separable so each carries its own test tooth:
// ① require the goal-named target or fail with error (never guess),
// ② forbid near-match substitution.
export function serverGuardSection() {
  return [
    "",
    "NON-NEGOTIABLE SERVER RULES (these OVERRIDE the GOAL wherever they conflict):",
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
