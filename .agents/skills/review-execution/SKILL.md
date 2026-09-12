---
name: review-execution
description: Review and correct one implemented task, or an in-scope mutation-test gap, using risk-based deterministic checks. Use at stage IMPLEMENTED or for an explicit REVIEWED-stage mutation handoff; do not use for new scope, mutation testing, or commits.
---

# Review Execution

## Goal

Produce an evidence-backed review of one implemented task and remove every in-scope critical, high, or medium defect before mutation testing.

## Fresh-context preflight

1. Read applicable `AGENTS.md`, Git status and diff, `nextTasks.md`, its source requirement, relevant code, tests, and build configuration. Do not rely on earlier review conclusions.
2. Select exactly one valid mode:
   - normal review requires stage `IMPLEMENTED`, completed task-to-requirement mappings, identifiable implementation diff, and recorded execution evidence;
   - mutation remediation requires stage `REVIEWED` and a `mutationReport.md` handoff that identifies an in-scope baseline gap or non-equivalent survivor, maps it to an existing requirement, and needs no product decision. Capture the predecessor `reviewReport.md` and triggering `mutationReport.md` hashes before editing.
3. Exclude unrelated dirty changes. If task-owned hunks cannot be distinguished safely, the source requirement is unavailable, or implementation evidence is materially incomplete, return `NEEDS_INPUT` without editing.
4. Validate the pipeline run ID, authoritative-source SHA-256 values, input artifact hashes, baseline commit, and task-diff fingerprint. A material mismatch returns `NEEDS_INPUT` before editing.
5. Discover available project checks from configuration; do not assume command names.
6. Set `Run status owner: review-execution` and `Run status: IN_PROGRESS` in `nextTasks.md` before review edits.

## Severity model

- `Critical`: exploitable security/privacy failure, irreversible data loss, or core behavior unusable with no safe workaround.
- `High`: required behavior is wrong or a likely crash, isolation breach, corruption, or major regression exists.
- `Medium`: meaningful edge case, maintainability, accessibility, localization, reliability, or test gap that can escape normal validation.
- `Low`: bounded improvement that does not invalidate requirements or materially raise release risk.

When uncertain between severities, state the impact and choose the higher defensible severity.

## Review workflow

1. Map every acceptance criterion to implementation and test evidence; treat a missing mapping as a finding.
2. Inspect changed code and affected call sites for correctness, regression risk, lifecycle/concurrency behavior, error paths, security/privacy, data ownership, encryption, permissions, networking, and secret leakage.
3. Select risk-based deterministic checks. Consider affected Android, iOS, desktop/shared, Firebase rules or Functions, SQLDelight/migrations, localization, accessibility, UI stability, and private-data isolation surfaces. Mark an unaffected surface `N/A` with a reason rather than running unrelated checks.
4. Run focused compilation/tests first, followed by applicable lint, static analysis, formatting, dependency/security, UI/device, and broader regression checks. Classify a failing baseline before correction:
   - reproducible and in scope: record a finding and enter the test-first fix loop;
   - unrelated or unattributable: return `NEEDS_INPUT` without editing it;
   - verified external/environmental: return `BLOCKED` with recovery evidence;
   - plausibly transient: allow at most one safe retry of the same non-mutating command, record both results, and treat inconsistent results as an in-scope reliability finding or an unrelated blocker rather than a pass.
5. Record findings with severity, requirement, file/location, evidence, impact, and proposed resolution.
6. Fix only in-scope defects that do not require a new product decision. If a finding exposes a missing product decision, make no production correction: set the authoritative spec status to `Needs Revision` as required by repository instructions, record before/after source hashes and the invalidated-plan reason, and return `NEEDS_INPUT` at the valid inbound stage.
7. Before every permanent correction, capture the current task-diff fingerprint and intended fix scope. Add a test that reproduces a production requirement failure. If automated reproduction is impossible for a non-production issue, record the concrete reason and an alternate deterministic reproduction before editing. After interruption, require exact attribution to the recorded fix scope or return `NEEDS_INPUT`.
8. In mutation-remediation mode, follow the handoff type: for a survivor, add or strengthen only the smallest permanent test; for a wrong/flaky baseline test, correct only that test; for a proven production regression, use the test-first correction loop. Do not repeat a temporary mutation in this skill. Record the exact baseline and mutation checks that must be rerun.
9. Never weaken, delete, or broadly skip a valid test to clear a finding. For every modified test, record its mapped requirement and before/after assertion intent.
10. Repeat review and affected checks until no known critical, high, or medium finding remains.

## Review artifact

Create or update `reviewReport.md` with:

- source task and reviewed diff scope;
- acceptance-criterion traceability;
- commands and concise results;
- affected-surface matrix, including justified `N/A` and skipped checks;
- findings, fixes, and rerun evidence;
- pipeline run ID, input `nextTasks.md` hash, reviewed diff fingerprint, source hashes, and any mutation-remediation handoff;
- in mutation-remediation mode, predecessor review hash, triggering mutation-report hash, unchanged run/source/baseline identity, and remediation diff fingerprint; the next mutation run calculates this successor report's hash after it is complete;
- remaining low risks and follow-up suggestions;
- final run status and workflow stage.

Do not stage, commit, delete task artifacts, or perform mutation testing.

Write and validate `reviewReport.md` first while preserving the current canonical stage in `nextTasks.md`. Update the report to its final status, then update the canonical stage marker last. If the artifacts disagree after an interruption, resume this skill, revalidate its definition of done, and repair the report before advancing; never infer success from only one artifact.

## Shared pipeline contract

- Run status is `IN_PROGRESS`, `NEEDS_INPUT`, `BLOCKED`, or `COMPLETE`.
- Workflow stage is `PLANNED`, `APPROVED`, `IMPLEMENTED`, `REVIEWED`, `MUTATION_VERIFIED`, or `FINALIZED`.
- `COMPLETE` means review is complete, not that the task is committed or finalized.
- `NEEDS_INPUT` requires a material decision or authorization. `BLOCKED` requires an evidenced external or environmental impediment. Neither is a passing review.
- Evidence must be sanitized: never store tokens, keys, receipts, raw media, unredacted user content, or full environment dumps; use redaction markers.
- Immediately before stage advancement, revalidate source hashes, inbound artifact hashes, task-diff fingerprint, and run-status ownership. In mutation-remediation mode, validate the successor lineage instead of requiring the predecessor review hash to remain current.

## Definition of done

Return `COMPLETE` and set `nextTasks.md` and `reviewReport.md` to stage `REVIEWED` only when:

- every acceptance criterion is traced to inspected code and passing or otherwise valid evidence;
- all applicable deterministic checks pass;
- every skipped or `N/A` check has a reason and no skip invalidates an acceptance criterion;
- no known critical, high, or medium finding remains after reruns;
- fixes stayed in scope and introduced no unrelated diff;
- the report preserves scope, evidence, findings, fixes, remaining low risks, and mutation-test handoff;
- in mutation-remediation mode, the mapped gap has a focused permanent test or test-first regression correction and an explicit mutation rerun handoff;
- `nextTasks.md` names `review-execution` as run-status owner and records `COMPLETE`.

Otherwise, preserve the valid inbound stage—`IMPLEMENTED` for normal review or `REVIEWED` for mutation remediation—and report `IN_PROGRESS`, `NEEDS_INPUT`, or `BLOCKED` with the unmet condition and next action.
