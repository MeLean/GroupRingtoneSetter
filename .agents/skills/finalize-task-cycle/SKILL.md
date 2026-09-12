---
name: finalize-task-cycle
description: Finalize one mutation-verified task by reconciling requirement records, preserving evidence, and optionally archiving, staging, or committing explicitly authorized files. Use only after mutation verification.
---

# Finalize Task Cycle

## Goal

Close one verified task safely, leaving its specification, evidence, worktree, and optional Git commit in an auditable final state.

## Fresh-context and authorization gate

1. Read applicable `AGENTS.md`, Git status and diffs, the current `nextTasks.md` or its retained manifest entry, `reviewReport.md`, `mutationReport.md`, the source requirement, and its reconciliation rules. Do not rely on earlier completion claims.
2. Before the normal stage gate, detect an `IN_PROGRESS` `finalizationReport.md` for the same run. Validate its action log, hashes, reconciliation diff, index fingerprint, and current files; resume from the last proven action without repeating it when attributable. If partial state is unattributable, return `NEEDS_INPUT` or an evidenced `BLOCKED` and perform no further action.
3. For a new run, require stage `MUTATION_VERIFIED`; for a validated resumed run, accept its recorded partial marker. In both cases require no unresolved non-equivalent survivor or medium-or-higher finding and identifiable task-owned files.
4. Validate the pipeline run ID, source hashes, baseline commit, task-diff fingerprint, and SHA-256 of `nextTasks.md`, `reviewReport.md`, and `mutationReport.md` before changing state.
5. Treat deletion of the task-owned `nextTasks.md` as required routine cleanup after a successful finalization. It does not need separate authorization, but only delete it after its run ID, final stage, and SHA-256 are preserved in `finalizationReport.md` and `taskCycleReport.md`. Separate permissions remain required for:
   - reconciliation edits explicitly required by the source requirement;
   - archive or deletion of task artifacts other than `nextTasks.md`;
   - Git staging and commit.
6. Classify each optional archive/delete, stage, and commit action:
   - `NOT_REQUESTED`: do not perform it; this does not block finalization.
   - `AUTHORIZED`: record the exact targets and authority before performing it.
   - `REQUESTED_UNAUTHORIZED`: return `NEEDS_INPUT` before the action and preserve prior state.
7. Authorization to finalize does not by itself authorize deletion of artifacts other than `nextTasks.md`, staging, or commit. If task-owned files cannot be isolated from unrelated changes, return `NEEDS_INPUT`. Never include unrelated hunks for convenience.

## Finalization workflow

1. Verify the existing reports satisfy their definitions of done. Do not rerun the entire pipeline unless evidence is stale or inconsistent.
2. Create `finalizationReport.md` with `Run status owner: finalize-task-cycle` at `IN_PROGRESS / MUTATION_VERIFIED` as the canonical durable record.
3. Complete only reconciliation fields required by the authoritative spec or plan. Record validated pre-reconciliation source hashes, post-reconciliation hashes, and the reconciliation diff fingerprint; do not change product decisions or claim checks that were not run.
4. Before deleting `nextTasks.md`, record its path, SHA-256, byte size, run ID, and final stage in `finalizationReport.md`; retain its requirement mapping and verification evidence through the finalization and cycle reports. Delete only this task-owned plan after that record is durable. Never delete `finalizationReport.md`: it is the permanent canonical record for the finalized run. Archive or delete any other artifact only when explicitly authorized and retained evidence passes the existing validation.
5. Before staging, fingerprint the complete pre-existing index. If it contains changes outside the authorized task set, return `NEEDS_INPUT` rather than unstaging, mixing, or committing them. If staging is authorized, stage only explicit task-owned paths or hunks, inspect the staged diff, and verify no unrelated or secret material is present.
6. If commit is authorized, commit only the inspected staged diff with a task-specific message. Record the commit SHA and verify the remaining worktree still contains every unrelated user change.
7. If the commit command fails, keep the authorized staged state inspectable, record the command and concise failure, preserve stage `MUTATION_VERIFIED`, and return `IN_PROGRESS` or an evidenced `BLOCKED`; never claim a commit or undo unrelated state.
8. If commit is `NOT_REQUESTED`, do not stage task changes unless staging alone was `AUTHORIZED`; preserve the pre-existing index exactly.
9. Validate the complete finalization report, delete the retained `nextTasks.md` plan, verify its absence, then set `finalizationReport.md` to `COMPLETE / FINALIZED` last. Retain `finalizationReport.md` after cleanup as the canonical stage marker.

## Finalization artifact

Record in `finalizationReport.md`:

- source requirement and finalized task IDs;
- implementation, review, and mutation artifact locations;
- reconciliation changes;
- pre/post reconciliation source hashes and reconciliation diff fingerprint;
- archived/deleted files and the authorization for each;
- retention manifest and post-copy verification for every cleaned artifact;
- action-state table for cleanup, staging, and commit;
- pipeline run ID, source hashes, input artifact hashes, task-diff fingerprint, and pre-existing index fingerprint;
- staged paths and staged-diff inspection result;
- commit SHA when authorized, or an explicit statement that no commit was made;
- remaining unrelated worktree changes and residual risks;
- final run status and workflow stage.

## Shared pipeline contract

- Run status is `IN_PROGRESS`, `NEEDS_INPUT`, `BLOCKED`, or `COMPLETE`.
- Workflow stage is `PLANNED`, `APPROVED`, `IMPLEMENTED`, `REVIEWED`, `MUTATION_VERIFIED`, or `FINALIZED`.
- `NEEDS_INPUT` requires a material decision or authorization. `BLOCKED` requires an evidenced external or environmental impediment. Neither is finalization.
- Evidence must be sanitized: never store tokens, keys, receipts, raw media, unredacted user content, or full environment dumps; use redaction markers.
- Immediately before deletion, staging, commit, or the final canonical status write, revalidate the relevant source/artifact hashes, task-diff fingerprint, index fingerprint, action authorization, and run-status ownership. A concurrent mismatch prevents the action.

## Definition of done

Return `COMPLETE` and record stage `FINALIZED` only when:

- implementation, review, and mutation evidence is internally consistent and still valid;
- every required specification reconciliation field is complete and evidence-based;
- the finalized task-owned `nextTasks.md` was retention-recorded, deleted, and its absence verified;
- `finalizationReport.md` remains present, validated, and retained as the canonical finalization evidence;
- task history remains auditable after any authorized archive or deletion;
- only explicitly authorized files or hunks were archived, deleted, staged, or committed;
- any staged diff was inspected and contains no unrelated or secret material;
- an authorized commit is recorded by SHA, or the action table records commit as `NOT_REQUESTED`;
- `finalizationReport.md` contains validated hashes/links and is the canonical `COMPLETE / FINALIZED` record;
- `finalizationReport.md` names `finalize-task-cycle` as run-status owner;
- unrelated user work remains untouched and the remaining worktree state is reported.

If an action is `REQUESTED_UNAUTHORIZED`, preserve stage `MUTATION_VERIFIED` and return `NEEDS_INPUT` naming the exact permission required. If evidence is invalid or a finalization action fails, keep the canonical report non-complete and state the recovery action.
