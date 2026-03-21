# LabelRingtoneSetter Agent Guide

## Communication
- Address the user as `My Friend`.
- Keep communication direct and concise.
- Never use `!!`.

## Core Coding Rules
- Follow `SOLID` and `KISS`.
- Prefer `val` over `var`.
- Avoid code duplication.
- Preserve existing app architecture and naming unless there is a clear reason to change them.

## User-Facing Text And Localization
- Do not hardcode user-facing strings in code.
- Add new user-facing text to string resources.
- Translate every new string in all supported locales:
  - `values`
  - `values-bg`
  - `values-de`
  - `values-fr`
  - `values-hi`
  - `values-it`
  - `values-ja`
  - `values-pl`
  - `values-ru`
  - `values-zh`

## Project Shape
- Android app module: `:app`
- Namespace: `com.milen.grounpringtonesetter`
- UI stack: Fragments, XML layouts, ViewBinding, Navigation component
- Main feature areas:
  - `ui/home`
  - `ui/picker`
  - `ui/defaulttones`
  - `ui/nointernet`
  - `billing`
  - `data`
  - `utils`
- Shared navigation graph: `app/src/main/res/navigation/nav_graph.xml`
- Telemetry and non-fatals flow through `Tracker`

## Build And Verification
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Instrumented tests: `./gradlew :app:connectedDebugAndroidTest`
- Lint: `./gradlew :app:lintDebug`
- Typical compile smoke check: `./gradlew :app:compileDebugKotlin`

Run the smallest relevant verification first, then broaden only if needed.

## Navigation And Error Handling
- Be careful with fragment navigation actions tied to a specific current destination.
- Prefer guarded navigation over assuming the current fragment is still active.
- If code currently suppresses an exception silently in an important path, prefer tracking a non-fatal rather than ignoring it.
- Reuse existing telemetry helpers before adding new logging patterns.

## Debug Workflow
When the user says `debug:` or asks to investigate a bug:

1. Ask for expected vs actual behavior, exact repro steps, and the fastest repro command or UI path.
2. Generate 2 to 5 hypotheses and map each one to a single observable signal.
3. Add minimal, removable instrumentation.
4. Prefer the existing logging and tracking stack.
5. Use a unique prefix like `CODEX_DEBUG` so logs are easy to grep.
6. Log inputs, outputs, key branches, timing, and error payloads. Do not log secrets.
7. Run the repro locally if possible. Otherwise ask the user to reproduce and provide logs.
8. Pick the most likely root cause, implement the smallest fix, and rerun repro.
9. Remove `CODEX_DEBUG` logs before finalizing unless the user explicitly asks to keep them behind a debug flag.

## Testing Expectations
- Add regression tests for bug fixes when the behavior can be reproduced deterministically.
- Prefer JVM tests for pure logic and Android tests for navigation or framework behavior.
- If you change strings, permissions, navigation, billing guards, or contact/ringtone flows, verify the relevant path explicitly.

## Review Expectations
- For code review requests, focus on findings first:
  - bugs
  - regressions
  - missing tests
  - risky assumptions
- Keep summaries short and secondary to findings.
