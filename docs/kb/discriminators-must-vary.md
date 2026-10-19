# A discriminator must actually vary

**Area:** verification / criteria
**Owner:** CC-Cata
**Verified at:** 2026-10-19
**Status:** current

> **Scope and lineage.** Distilled from one day of adjacent failures on this repo, several of them
> mine and several pointed out by reviewers. It generalises the criterion in
> `numeric-criteria-that-pass-while-the-feature-is-broken.md` from "does this number prove the
> feature" to "does this signal distinguish anything at all". Nothing here has been superseded.

A **discriminator** is anything you use to tell two states apart: a test, a log line, a metric, a
toggle, a measurement. Four ways they fail, all of which produce a confident wrong answer rather than
an error.

## 1. It never varies

A signal that is identical in both states distinguishes nothing, and reads as "no difference found".

A class-name signature was proposed to detect "the probe attached to a different list". Every list in
this app is the same class, so the signature was a constant — it could not report a change even if
one occurred. The fix was an instance identity already present in the payload.

Same bone as a test that can never fail: **the check's inability to fire is invisible in its output.**
The output looks the same whether the states differ or not.

## 2. It does not reach the surface you read it from

"Code contains a log statement" is not "the log appears where you plan to grep". A diagnostic line
written through a wrapper whose verbosity flag is off silently produces nothing, and the empty result
then gets read as *the condition was false*.

This direction is the dangerous one: **an absence is read as a negative finding, when the real cause
may be that nothing was ever written there.** Any "we saw zero of X" needs a positive control — a
companion line that must appear if the path ran at all.

## 3. It is not measured where you assume the value comes from

`viewportStartOffset` read `-134`, which looks like a scroll past the content start. It is defined as
`-afterContentPadding` for a reverse list — the correct, constant representation of a bottom padding.
An "impossible" value is a question about the contract, not evidence of a defect.

Check the definition before treating a number as an observation.

## 4. It is satisfied by an implementation that fails the user

`window.innerWidth === 1280` passed while the page was drawn at 100% and the user could see about
480px of it. The criterion described what the code should *do*, not what the user must *experience*.
Full treatment in `numeric-criteria-that-pass-while-the-feature-is-broken.md`.

## The question that catches all four

Before trusting a signal, ask: **what would this report if the thing I am testing for were true, and
what would it report if it were false?** If the two answers are the same — or if you cannot say what
either would be — the signal is not evidence yet.

For a test, run it against the broken code. For a log line, confirm it appears on the surface you
will read. For a metric, read its definition. For a criterion, name the user-visible outcome first
and find a measurement the wrong implementation cannot satisfy.

## Related

- `numeric-criteria-that-pass-while-the-feature-is-broken.md` — criteria satisfied by broken code
- `declaring-your-verification-ceiling.md` — what a claim at each layer can and cannot support
