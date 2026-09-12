---
name: determine-next-tasks
description: Create an autonomous nextTasks.md for one implementation-eligible requirement, or prove that no tasks remain, by reconciling it with the current codebase. Use before coding or for a completion check; do not use to implement tasks.
---

# Determine Next Tasks

## Goal

Produce one evidence-based planning outcome—an implementation-ready `nextTasks.md` or `NO_TASKS_REMAIN`—without implementing code or silently deciding unresolved product behavior. A complete plan is authorized for the normal local implementation pipeline; do not request routine plan approval.

## Fresh-context input gate

1. Read the applicable `AGENTS.md`, current Git status, planning documents, relevant code, and tests. Treat repository files and the current request as authoritative; do not reuse conclusions from earlier runs.
2. Select exactly one authoritative requirement. For new planning in this project, it must be one `Ready` feature spec, plus every ADR or architecture document it references. When several specs are `Ready`, select the first dependency-ready entry in `spec-map.md`'s recommended refinement order, falling back to ascending spec ID; record that choice. For an explicit completion check, a named source from the current finalized cycle may be inspected after its status changes, but no new implementation may be planned unless it is `Ready`.
3. Stop with `NEEDS_INPUT` instead of creating or replacing the task file when:
   - for completion checking, the named cycle source is missing; authoritative sources conflict; or a candidate cannot be selected by the documented ordering;
   - the requirement is a draft, deferred, or missing a material product decision;
   - relevant code cannot be inspected;
   - an existing `nextTasks.md` belongs to an active, conflicting, or unattributable run and cannot be safely retained or replaced.
4. Ask only the smallest question that resolves the material ambiguity. Never invent product behavior.

## Human-prerequisite gate

Classify each prerequisite that can prevent execution:

- `AGENT_EXECUTABLE`: executable with current tools and authority.
- `AGENT_REQUIRES_APPROVAL`: executable only after an explicit permission gate for an external, destructive, legal, financial, publication, or similarly consequential action.
- `HUMAN_REQUIRED`: requires a person, such as account creation, legal or billing acceptance, possession of a physical device, or final visual/store approval.
- `EXTERNAL_BLOCKER`: depends on an unavailable service or organization.
- `PRODUCT_DECISION_REQUIRED`: behavior or design is not specified.

Tokens, credentials, signing identities, service registration, third-party console configuration, device access, and manual verification must be checked explicitly. Do not request, read, print, or store raw secrets; record only non-sensitive evidence such as `configured`, `not configured`, or `connection verified`.

UI work is not automatically human-required: specified UI is `AGENT_EXECUTABLE`; missing behavior or design is `PRODUCT_DECISION_REQUIRED`; subjective final approval may be `HUMAN_REQUIRED`.

Map unresolved prerequisites deterministically:

- `AGENT_REQUIRES_APPROVAL`, `HUMAN_REQUIRED`, or `PRODUCT_DECISION_REQUIRED` returns `NEEDS_INPUT` with the owner and smallest required action. Routine local planning, implementation, tests, review, and reconciliation are `AGENT_EXECUTABLE`.
- `EXTERNAL_BLOCKER` returns `BLOCKED` with non-sensitive availability evidence and a retry or recovery condition.
- Missing token/account setup asks the owner to configure it through an appropriate secure mechanism and report only `configured` or `connection verified`; never ask them to paste the secret.

Apply the outcome table:

| Prerequisite effect | Result |
| --- | --- |
| Blocks the whole unit and needs approval, human action, account/token setup, or product choice | `NEEDS_INPUT`; create no task file |
| Blocks the whole unit because a verified external service is unavailable | `BLOCKED`; create no task file |
| Can be excluded without weakening an acceptance criterion | Plan the independent unit as `COMPLETE / PLANNED`; record the deferred work, owner, and prerequisite as explicit non-goals |

## Planning workflow

1. Map requirement IDs and acceptance criteria to current implementation and tests.
2. Determine the planning outcome:
   - `TASKS_PLANNED` when at least one requirement-backed, dependency-ready unit remains;
   - `NO_TASKS_REMAIN` only when every requirement and acceptance criterion is reconciled as implemented, required verification evidence is current, no task/remediation remains open, and any matching lifecycle reports prove stage `FINALIZED`.
   - when no `Ready` spec remains after the current finalized-cycle reconciliation, return portfolio-level `NO_TASKS_REMAIN`; Raw Draft, Needs Revision, In Discussion, and Deferred specs are not silently promoted or implemented.
   If implementation appears complete but required completion evidence is absent or stale, return `NEEDS_INPUT` naming the exact evidence or verification needed; do not infer completion.
3. For `TASKS_PLANNED`, select the smallest dependency-ready missing unit with no unresolved product decision. Follow the authoritative requirement order; if equally eligible choices would change scope or behavior, return `NEEDS_INPUT` rather than choosing arbitrarily.
4. Define scope and explicit non-goals. Exclude unrelated dirty-tree changes.
5. Order tasks by dependency and place automated test changes before production changes.
6. Discover exact, available verification commands from project configuration. Do not invent task names.
7. Capture a provenance block: unique run ID, current baseline commit, SHA-256 for every authoritative source and relevant code/test file inspected, and a manifest of pre-existing task-owned paths or diff hunks.
8. Draft and validate the complete outcome before writing. Immediately before the write or completion claim, recheck the baseline commit, all captured hashes, current dirty-scope attribution, and run-status ownership; restart reconciliation or return `NEEDS_INPUT` on an unattributable mismatch. A prior finalized or complete pipeline artifact may be superseded automatically after its evidence is retained; never replace an active or unattributable artifact.
9. For `NO_TASKS_REMAIN`, do not create or replace `nextTasks.md`. Report the source, requirement-to-evidence reconciliation, matching finalization evidence, provenance, and `Run status: COMPLETE`.
10. For `TASKS_PLANNED`, preserve a prior finalized or complete file until the validated replacement can be applied as one file operation. Re-read the persisted file, verify its SHA-256 and parsed contract match the draft, and repeat the provenance checks before reporting completion. Create `nextTasks.md` with this contract:

```markdown
# Next Tasks
Planning outcome: TASKS_PLANNED
Run status owner: determine-next-tasks
Run status: COMPLETE
Workflow stage: PLANNED
Approval: AUTONOMOUS
Source requirement: <path and requirement IDs>

## Pipeline provenance
| Run ID | Baseline commit | Source/code/test paths and SHA-256 | Pre-existing task-owned scope |
| --- | --- | --- | --- |
## Scope and non-goals
## Requirement-to-task map
## Human prerequisites
| ID | Prerequisite | Classification | Owner | Status/evidence | Blocked task IDs |
| --- | --- | --- | --- | --- | --- |
## Ordered tasks
- [ ] T1 ...
## Verification
| Command/check | Acceptance criterion | Required or N/A with reason |
| --- | --- | --- |
## Dependencies and unresolved decisions
## Planning evidence
```

Every task must have a stable ID, affected area, expected observable result, and mapped requirement ID. Include unit, integration, UI, isolation, accessibility, localization, security, and platform-specific tests when applicable; mark a category `N/A` only with a requirement-based reason.

## Shared pipeline contract

- Run status is `IN_PROGRESS`, `NEEDS_INPUT`, `BLOCKED`, or `COMPLETE`.
- Workflow stage is `PLANNED`, `APPROVED`, `IMPLEMENTED`, `REVIEWED`, `MUTATION_VERIFIED`, or `FINALIZED`.
- `COMPLETE` means this skill's definition of done is satisfied; it does not mean the implementation lifecycle is complete.
- `NEEDS_INPUT` means a material user decision or authorization is required. `BLOCKED` means a verified external or environmental condition prevents progress. Never report either as completion.
- The artifact containing `Run status` must also name its owning skill. A downstream skill sets itself as owner and `IN_PROGRESS` before material work, then records its own terminal status.
- Store only sanitized evidence. Never persist tokens, keys, receipts, raw media, unredacted private/user content, or full environment dumps; use redaction markers and non-sensitive summaries.

## Definition of done

Return `COMPLETE` only when:

- exactly one eligible authoritative requirement, or one explicitly named finalized-cycle completion target, and its referenced constraints were inspected;
- the current code and tests were reconciled against every selected requirement ID;
- the provenance block permits downstream source-staleness and dirty-scope checks by exact comparison;
- human prerequisites, dependencies, non-goals, and unresolved decisions are explicit;
- no production code was changed and no active or unattributable task file was overwritten;
- one outcome-specific gate passes:
  - `TASKS_PLANNED`: `nextTasks.md` follows the contract at stage `PLANNED`, is marked `Approval: AUTONOMOUS`, contains ordered test-first tasks mapped to requirements, records exact verification commands and justified `N/A` checks, and passed post-write validation;
  - `NO_TASKS_REMAIN`: every requirement and acceptance criterion has current completion/finalization evidence, no task file was created or replaced, and the completion evidence and provenance are reported.

Otherwise, report the unmet condition, evidence inspected, and the single next action under `NEEDS_INPUT`, `BLOCKED`, or `IN_PROGRESS`.
