---
name: execute-next-tasks
description: Implement one autonomous nextTasks.md with test-first changes and recorded verification evidence. Use after planning; do not use for review, mutation testing, or finalization.
---

# Execute Next Tasks

## Goal

Implement exactly the planned tasks in `nextTasks.md`, test first, and hand off a verified implementation for independent review. A valid autonomous plan authorizes normal local implementation and verification work.

## Fresh-context input gate

1. Read applicable `AGENTS.md`, current Git status, `nextTasks.md`, its authoritative requirement, and relevant code and tests. Do not rely on earlier conversation conclusions.
2. Require exactly one task file with stage `PLANNED` (or legacy `APPROVED`), a complete source mapping and provenance block, `Approval: AUTONOMOUS` (or legacy explicit approval), no unresolved material decision, and executable verification commands. Recalculate its source hashes, baseline commit, and pre-existing task-scope manifest; return `NEEDS_INPUT` when they show a material stale or conflicting plan.
3. Return `NEEDS_INPUT` without production edits when the task file is missing, ambiguous, stale, materially incomplete, conflicts with its source requirement, or requires a consequential external action that the user has not authorized. Do not request approval for routine local code, tests, review handoff, or verification.
4. Identify pre-existing dirty changes. Continue only when task-owned files and diff hunks can be isolated without overwriting, reverting, staging, or reformatting unrelated work; otherwise return `NEEDS_INPUT` with the collision.
5. Before each task, record its task-owned diff fingerprint. After an interruption, compare that fingerprint, task evidence, and current files to determine the last proven step; rerun only unproven checks and return `NEEDS_INPUT` if partial edits cannot be attributed safely.
6. After gates pass, set `Run status owner: execute-next-tasks` and `Run status: IN_PROGRESS` in `nextTasks.md` before material implementation work.

## Test-first execution loop

For each ordered task:

1. Reconfirm its requirement ID, scope, and expected observable behavior.
2. Add or update the smallest automated test that proves the behavior. A valid red signal must directly demonstrate the mapped requirement: an expected assertion mismatch or a requirement-defined compile/API gap. Infrastructure, unrelated compilation, or setup failures do not count.
3. Run the test and record the valid expected failure. If it passes before implementation:
   - when the test is ineffective, keep `IN_PROGRESS / PLANNED`, strengthen it until it produces a valid red signal, and record the rejected test evidence;
   - when the test validly proves the behavior already exists, make no production edit. Retain and fingerprint the independently useful test, mark the task complete, record the discrepancy under `Planning discrepancy` in `nextTasks.md`, and continue with the remaining in-scope tasks. Return to planning only when the discrepancy makes remaining task scope or acceptance evidence ambiguous.
4. If the test fails only from infrastructure, setup, or unrelated compilation, make no production edit, record the invalid-red command and sanitized diagnostic in `nextTasks.md`, and return `IN_PROGRESS / PLANNED` or an evidenced `BLOCKED / PLANNED` for an external/environmental cause.
5. Make the smallest production change that makes the focused test pass, following the repository architecture and conventions.
6. Rerun the focused test until it passes. Do not weaken a valid test to obtain a pass.
7. Run the smallest affected regression set, then mark the task complete only when its acceptance evidence is present.

Use integration, UI, isolation, accessibility, localization, security, and platform-specific tests when required by `nextTasks.md` or the source requirement. If a required check cannot run, preserve its command and diagnostic evidence; do not silently downgrade it.

Every production-behavior task requires valid red/green evidence. A non-production task may use an alternate deterministic check only when `nextTasks.md` records it as `N/A` for automated tests with a requirement-based reason.

## Scope and failure handling

- Ask before any material scope expansion or source-requirement change.
- If implementation exposes a missing product decision, stop, follow the repository's spec-status rule, and return `NEEDS_INPUT`; do not invent behavior.
- Use `BLOCKED` only for a verified external or environmental condition after safe diagnostic checks. Include the failed command, concise output, affected task IDs, and recovery action.
- Do not delete task artifacts, perform mutation testing, stage files, or commit.

## Evidence and handoff

Update `nextTasks.md` without erasing its planning record:

- task checkboxes and per-task acceptance evidence;
- files changed for each task ID;
- red-phase command and failure summary;
- green/regression commands, results, and relevant skipped checks with reasons;
- remaining risks or blockers;
- `Workflow stage: IMPLEMENTED` only after the definition of done passes;
- autonomous-plan SHA-256, final source hashes, final task-diff fingerprint, and the pipeline run ID.

## Shared pipeline contract

- Run status is `IN_PROGRESS`, `NEEDS_INPUT`, `BLOCKED`, or `COMPLETE`.
- Workflow stage is `PLANNED`, `APPROVED`, `IMPLEMENTED`, `REVIEWED`, `MUTATION_VERIFIED`, or `FINALIZED`.
- `COMPLETE` means this skill's definition of done is satisfied; it does not mean review or finalization is complete.
- `NEEDS_INPUT` requires a material decision or authorization. `BLOCKED` requires an evidenced external or environmental impediment. Neither is completion.
- Evidence must be sanitized: never store tokens, keys, receipts, raw media, unredacted user content, or full environment dumps; record redaction markers where output was removed.
- Immediately before setting stage `IMPLEMENTED`, revalidate source hashes, autonomous-plan identity, task-diff fingerprint, and current artifact ownership/status. A concurrent mismatch prevents advancement.

## Definition of done

Return `COMPLETE` and set stage `IMPLEMENTED` only when:

- the exact autonomous task file remained consistent with its authoritative requirement;
- every in-scope task and acceptance criterion is implemented and marked complete;
- required tests were created before their production changes, with red and green evidence recorded;
- focused and affected regression checks pass;
- every skipped check has a valid reason and does not invalidate an acceptance criterion;
- the final diff contains no known unrelated edits and no unresolved decision or blocker;
- `nextTasks.md` contains the implementation evidence and review handoff;
- `nextTasks.md` names `execute-next-tasks` as run-status owner and records `COMPLETE`.

If any condition fails, preserve its valid inbound stage. Use the accurate non-complete run status and report the next concrete action.
