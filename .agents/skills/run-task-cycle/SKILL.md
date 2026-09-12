---
name: run-task-cycle
description: Autonomously orchestrate planning, implementation, review, mutation, and finalization across eligible feature specifications until none remain or a genuine human/external stop is reached. Use for a resumable end-to-end task loop; preserve consequential-action safety gates.
---

# Run Task Cycle

## Goal

Drive eligible authoritative requirements through the existing skill pipeline, one owner at a time, until no eligible feature specification has tasks remaining or a truthful human/external stop is reached. Complete one requirement at a time; after it is reconciled, select the next eligible requirement.

This skill routes and records work. It does not replace child-skill product decisions, broaden scope, or grant permission for destructive, external, or Git actions. It does authorize the normal local pipeline once a child produces a valid autonomous plan.

## Input and authorization gate

1. Read applicable `AGENTS.md`, current Git status, and the complete current instructions for `determine-next-tasks`, `execute-next-tasks`, `review-execution`, `do-mutation-tests`, and `finalize-task-cycle`.
2. Resolve exactly one target requirement at a time. In this project, select one `Ready` feature spec, complete its pipeline without switching, then return to planning to select the next `Ready` feature spec. Stop `COMPLETE` only when planning finds no remaining eligible work across the specifications.
3. Locate `nextTasks.md`, `reviewReport.md`, `mutationReport.md`, `finalizationReport.md`, and any prior cycle report. Group artifacts by pipeline run ID and validate their hashes/stages before routing. Conflicting runs or unattributable dirty changes return `NEEDS_INPUT` without child execution.
4. Record only permissions the user actually granted:
   - a valid `Approval: AUTONOMOUS` plan authorizes normal local planning, implementation, testing, review, mutation verification, and required specification reconciliation;
   - task-owned `nextTasks.md` deletion after a successful finalization is routine cycle cleanup; `finalizationReport.md` is retained permanently; all other archive/delete, staging, commit, publication, paid actions, advertising, account creation, legal acceptance, credentials, and external-service configuration remain separate;
   - optional finalization actions default to `NOT_REQUESTED`.
5. Invoking this orchestration skill permits a valid autonomous plan to enter the normal local pipeline and remove the finalized task-owned `nextTasks.md`, but does not authorize staging, committing, deleting other artifacts, creating accounts, supplying credentials, or mutating external services.

## Canonical router

Select exactly one next skill from current canonical state:

| State | Route |
| --- | --- |
| No active run, or the previous run is `FINALIZED` | `determine-next-tasks` to select the next eligible requirement |
| Planning outcome `NO_TASKS_REMAIN` with `COMPLETE` evidence | Stop `COMPLETE` |
| Stage `PLANNED` with `Approval: AUTONOMOUS`, or legacy stage `APPROVED` | `execute-next-tasks` |
| Stage `PLANNED` that requires a consequential action not authorized by the user | Stop `NEEDS_INPUT` with the smallest required authorization |
| Stage `IMPLEMENTED` | `review-execution` normal mode |
| Stage `REVIEWED` with an unresolved mutation baseline/survivor handoff | `review-execution` mutation-remediation mode |
| Stage `REVIEWED` with no unresolved handoff, or with a completed successor review | `do-mutation-tests` |
| Stage `MUTATION_VERIFIED` | `finalize-task-cycle` |
| An attributable `IN_PROGRESS` child run | Resume that owner, except mutation handoffs route to review as above |
| Canonical status `NEEDS_INPUT` or `BLOCKED` | Stop without invoking another child |
| Unknown, conflicting, or impossible state | Stop `NEEDS_INPUT` with the conflicting evidence |

Before each child run, read its `SKILL.md` completely and follow it as the active procedure. Run children sequentially; never execute two owners concurrently or precompute a later stage from unvalidated earlier output.

## Loop protocol

1. Capture a progress fingerprint excluding timestamps and this cycle report: target source/hash, pipeline run ID, stage, run-status owner/status, unresolved task/finding/mutant IDs, task-diff fingerprint, and child artifact hashes.
2. Append an `IN_PROGRESS` ledger row to `taskCycleReport.md`, then invoke the routed child within its existing scope and authority.
3. Validate the child's definition of done and persisted handoff; do not route from a conversational success claim alone. For `NO_TASKS_REMAIN`, revalidate the reported source/finalization hashes and requirement reconciliation directly, then persist that terminal evidence in `taskCycleReport.md` before stopping; no `nextTasks.md` is required for this outcome.
4. Append the child's output stage/status, sanitized evidence, artifact hashes, and next route to the ledger.
5. Continue automatically when:
   - the child returns `COMPLETE` and the router identifies a safe next stage; or
   - it returns `IN_PROGRESS`, a deterministic safe action remains, and the progress fingerprint changed.
6. Permit at most one child-authorized bounded retry with an unchanged meaningful fingerprint. A second consecutive unchanged result for the same owner and unresolved IDs stops as `NEEDS_INPUT / CYCLE_STALLED`; report what repeated and the smallest human decision needed. Do not spin or weaken checks.
7. After `FINALIZED`, route back to `determine-next-tasks` to select the next eligible requirement. When it returns another valid autonomous plan, start the next pipeline run immediately. Stop when planning returns `NO_TASKS_REMAIN` across the eligible specifications.

## Mandatory stop conditions

Stop immediately and preserve the current stage when any child reports:

- `NEEDS_INPUT`, including missing product/design decisions, human verification, account/token/service configuration, unisolatable changes, or requested-but-unauthorized consequential actions;
- `BLOCKED`, including external environment failure or mutation restoration failure;
- a source/artifact/hash mismatch, unknown stage, conflicting run ID, or loss of task ownership;
- a request for raw secrets or any action outside the recorded authorization envelope.

For a human stop, report only the minimum actionable request: owner, reason, blocked task IDs/stage, exact artifact path and hash when consequential authorization is needed, non-sensitive evidence, and the condition for resuming. Never request raw secrets; ask the human to configure them securely and confirm only non-sensitive state.

## Cycle artifact

Create or update `taskCycleReport.md` as orchestration-owned evidence and exclude it from child task diffs and Git finalization unless the user explicitly includes it. Record:

- current requirement and portfolio completion target;
- authorization envelope and autonomous-plan artifact hashes;
- current pipeline run ID, stage, owner, and status;
- iteration number, routed skill, input/output fingerprints, artifact hashes, concise result, and next route;
- human/external stops and resume conditions;
- terminal reconciliation for every completed requirement and portfolio-level `NO_TASKS_REMAIN` evidence.

Sanitize all evidence. Never store tokens, keys, receipts, raw media, unredacted user content, or full environment dumps.

## Definition of done

Return exactly one terminal result:

- `COMPLETE`: `determine-next-tasks` returned `NO_TASKS_REMAIN` across eligible feature specifications with current reconciliation/finalization evidence; no active task, finding, remediation, or mutant remains.
- `NEEDS_INPUT`: a material human decision, secure configuration, consequential authorization, ownership resolution, or cycle-stall decision is required. The minimum request and resume condition are recorded.
- `BLOCKED`: an evidenced external/environmental or restoration condition prevents safe progress. Recovery evidence and the safe resume condition are recorded.

Do not return terminal `IN_PROGRESS`. While a safe authorized action remains and progress is changing, continue the loop. Never report `COMPLETE` merely because one child skill completed, an iteration limit was reached, or optional Git actions were not requested.
