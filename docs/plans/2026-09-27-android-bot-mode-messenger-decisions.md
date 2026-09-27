# Owner decisions — Bot Mode messenger + status (2026-09-26)

Recorded from Pedro's replies. These bind the design; the plan must not contradict them.

1. **"Close" = archive.** Confirmed. The host has no user-facing "closed" state — only `archived`
   (durable upstream field, `PATCH /api/sessions/{id}`), delete, and prune. "Open conversations" =
   `archived=exclude` (the default read). Archived rows stay restorable. No second local flag registry
   (ADR 46/48, `docs/decisions.md:2810-2811`).

2. **The status is an indicator that *reacts* — not a "done" tick.** Confirmed interpretation: he does not
   require a "completed" state; he wants the indicator to reflect the current situation as it changes.
   Consequence: no fake "done" light. Where nothing is running the row shows **recency** (and, per the
   judge, "done" is *not distinguishable* from "we don't know" — `SessionActivityRegistry.kt:96` vs `:102`).
   The indicator is honest about what it knows.

3. **Status appears only for conversations that actually have one.** Confirmed: no polling of every saved
   machine for a fleet-wide light. This rejects the design's "Option A" (per-gateway `session.active_list`
   lease per refresh) — status is shown where attribution is real (the judge's B3/B5 findings), and
   non-active/unloaded sources show recency only, never an invented state.

4. **Tab strip placement — open.** He does not yet understand the top-tabs vs side-rail distinction
   (question answered with mockup v2 showing both on the same conversation screen).

## Standing constraints carried into the plan

- ADR 48: "no fake activity indicator"; "colour never communicates activity or health" → the status must
  carry a label/icon, never colour alone.
- ADR 12: one Chat surface, no second nav lane/segment; bottom nav stays 4 tabs.
- ADR 67: Bot Mode is one drawer entry, full-screen messenger list; identity = `(connectionId, profile)`.
- Never hot-swap a live turn; passive open is read-only (`docs/spec.md:624`, `docs/decisions.md:2907-2911`).
- Supervised Mode extended on day one, affordances **absent** rather than disabled
  (`docs/decisions.md:3874-3877`).
- Every new string in canonical + 6 locale catalogs (+ flavor catalogs) with refreshed hashes
  (`docs/localization.md:19-60`); rendered behaviour is a human/device gate (`docs/dev-loop.md:62`).
