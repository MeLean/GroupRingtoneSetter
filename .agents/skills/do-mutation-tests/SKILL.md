---
name: do-mutation-tests
description: Evaluate whether tests kill risk-based mutations in reviewed production changes, while proving every temporary edit is restored. Use at workflow stage REVIEWED; do not use to implement fixes, delete artifacts, stage, or commit.
---

# Do Mutation Tests

## Goal

Measure whether the tests detect meaningful defects in the reviewed production changes and produce a restoration-proven mutation report.

## Fresh-context and scope gate

1. Read applicable `AGENTS.md`, Git status and diff, `nextTasks.md`, `reviewReport.md`, changed production code, tests, and build configuration. Do not rely on previous mutation conclusions.
2. Require consistent stage `REVIEWED`, no unresolved critical/high/medium finding, an identifiable task diff, and the review report's applicable-surface matrix with passing baseline checks.
3. Return `NEEDS_INPUT` without mutating files when artifacts conflict, scope is ambiguous, task hunks overlap unrelated edits, or reversible restoration cannot be proven.
4. Validate the pipeline run ID, authoritative-source SHA-256 values, `reviewReport.md` hash, baseline commit, and reviewed task-diff fingerprint. For a remediation rerun, accept a changed review hash only when the successor report records the exact current predecessor mutation-report hash, predecessor review hash, unchanged run/source/baseline identity, and an in-scope remediation diff fingerprint. Adopt the successor review hash and new reviewed-diff fingerprint for this run; any broken lineage returns `NEEDS_INPUT`.
5. Prefer a configured mutation-testing tool that isolates mutations. Discover its actual commands; do not assume a plugin exists.
6. Set `Run status owner: do-mutation-tests` and `Run status: IN_PROGRESS` in `nextTasks.md` before creating temporary mutations.

## Safety and restoration invariant

- Never delete, revert, stage, commit, reset, clean, or reformat project or user work. The only removable file is a recovery copy created by this run after restoration is proven.
- Apply one mutation at a time to one explicitly selected production location.
- Before manual mutation, record the selected file's content hash and exact task diff, and create a uniquely named recovery copy outside the worktree with access limited to the current user. Record its path, owner, permissions, and SHA-256; do not expose sensitive contents in the report.
- Restore the exact pre-mutation bytes immediately after each test run, including failed or interrupted runs, then verify the content hash and task diff match the captured baseline.
- If restoration verification fails, stop all mutations and preserve the recovery-copy path. Make no further production or test edits and do not retry restoration automatically; using captured metadata only, update `mutationReport.md` and the canonical run owner/status to `BLOCKED / REVIEWED` so recovery evidence is durable.
- After all restoration and final-baseline checks pass, remove only the recovery copies created by this run and record `REMOVED`. If cleanup fails, preserve their paths and return `IN_PROGRESS`; never claim they were removed.

## Mutation workflow

1. Build a changed-decision matrix for every changed condition, boundary, return, error/null path, state transition, side effect, and authorization/isolation decision. Map each row to a requirement and either a planned mutation or an explicit equivalence/inapplicability reason. Prioritize authorization/isolation and side effects, then conditions/boundaries, returns/state, and error paths; use the first non-equivalent operator per row unless additional operators cover observably different risk.
2. Run the focused baseline tests. A failure invalidates mutation results; persist the matrix and first unexecuted row in the remediation handoff, then classify it as:
   - wrong, flaky, or missing in-scope test, or an in-scope production regression: create a precise remediation handoff and return `IN_PROGRESS / REVIEWED`;
   - unrelated or unattributable: return `NEEDS_INPUT / REVIEWED` without changing it;
   - verified external/environmental: return `BLOCKED / REVIEWED` with recovery evidence.
   On a remediation rerun, first verify the handed-off baseline now passes and adopt the completed successor review's calculated SHA-256. For a baseline-gap handoff, resume at the first unexecuted changed-decision row. For a survivor handoff, rerun the named surviving mutation first, record whether it is now killed, then continue with unexecuted or invalidated rows.
3. For each planned mutation:
   - identify the requirement, location, operator, and test expected to kill it;
   - apply only that mutation;
   - run the smallest valid detecting test set;
   - classify it as `KILLED`, `SURVIVED`, or `EQUIVALENT`, with command evidence;
   - restore and verify the baseline before continuing.
4. Treat a mutant as `EQUIVALENT` only when observable behavior cannot differ under the requirement and reachable inputs; record the reasoning.
5. A non-equivalent survivor is evidence of a test gap. Record the missing assertion and smallest recommended test, keep workflow stage `REVIEWED`, and hand it to `review-execution` mutation-remediation mode. Do not silently edit permanent tests or production code in this skill.
6. After all mutations, rerun the baseline focused checks and prove no temporary mutation remains.

## Mutation artifact

Create or update `mutationReport.md` with:

- source task, reviewed diff, tool/manual method, and baseline evidence;
- mutation plan and justified omissions;
- pipeline run ID, source hashes, input report hashes, reviewed diff fingerprint, and changed-decision matrix;
- predecessor mutation-report hash and accepted successor-review lineage on remediation reruns;
- each mutation's requirement, location, operator, expected detecting test, command result, classification, and restoration proof;
- equivalent-mutant reasoning and surviving-mutant handoff;
- final baseline rerun, repository-scope check, run status, and workflow stage.

Write and validate `mutationReport.md` first while keeping the canonical stage in `nextTasks.md` at `REVIEWED`. Update the report to its final status, then update the canonical stage to `MUTATION_VERIFIED` last. If the artifacts disagree after interruption, resume this skill, verify restoration first, and revalidate the definition of done before advancing.

## Shared pipeline contract

- Run status is `IN_PROGRESS`, `NEEDS_INPUT`, `BLOCKED`, or `COMPLETE`.
- Workflow stage is `PLANNED`, `APPROVED`, `IMPLEMENTED`, `REVIEWED`, `MUTATION_VERIFIED`, or `FINALIZED`.
- `COMPLETE` means mutation verification passed; it does not authorize cleanup or Git history changes.
- `NEEDS_INPUT` requires a material decision or authorization. `BLOCKED` requires an evidenced restoration, external, or environmental impediment. Neither is success.
- Evidence must be sanitized: never store tokens, keys, receipts, raw media, unredacted user content, recovery-copy contents, or full environment dumps; use redaction markers.
- Immediately before setting `MUTATION_VERIFIED`, revalidate source and accepted report hashes, task-diff fingerprint, restoration proofs, and run-status ownership. A concurrent mismatch prevents advancement.

## Definition of done

Return `COMPLETE` and set `nextTasks.md` and `mutationReport.md` to stage `MUTATION_VERIFIED` only when:

- the reviewed baseline passed and every changed-decision matrix row has a mutation or defensible equivalence/inapplicability reason;
- every planned mutation has evidence and is `KILLED` or defensibly `EQUIVALENT`;
- no non-equivalent survivor remains;
- every manual mutation was restored with matching content hash and task diff;
- final baseline checks pass and repository inspection finds no temporary mutation or unrelated edit from this skill;
- the report contains reproducible commands, classifications, restoration proof, and finalization handoff;
- every recovery copy created by a successful run has recorded lifecycle evidence and was removed after restoration, or the run remains non-complete with its safe path reported;
- `nextTasks.md` names `do-mutation-tests` as run-status owner and records `COMPLETE`.

If a survivor or baseline gap remains, report `IN_PROGRESS` at stage `REVIEWED`. If restoration or infrastructure prevents safe continuation, report the accurate non-complete status and next action.
