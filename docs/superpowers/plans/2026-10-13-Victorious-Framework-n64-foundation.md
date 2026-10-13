# Victorious Framework — N64 Emulator Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Produce a reproducible, source-built N64 Android APK as the foundation for the approved universal phone/Onn application.

**Architecture:** Begin with a source checkout of Mupen64Plus Android Edition and preserve its working emulator integration. Verify the unmodified baseline before changing application behavior. Subsequent milestone plans add the approved Play, Display, and Controller modes without replacing proven emulation code.

**Tech Stack:** Existing upstream Android/Java/Gradle/NDK stack; exact versions and native plugins are read from the pinned checkout, not guessed from the uploaded APK.

**Spec:** Victorious-Framework-Universal-N64-App-Design.md (approved 2026-10-13).

## Global Constraints
- Universal means one maintained Android codebase with runtime capability detection, adaptive layouts, and supported CPU builds.
- Use an existing N64 emulator core rebuilt from its maintained source with its license obligations preserved.
- Do not use the uploaded binary as a verified build dependency.
- Do not place the ROM in a public source repository.
- Building an APK alone is not evidence gameplay, casting, or physical controllers work.
- First receiver target: Onn devices running Android TV or Google TV; exact model remains unverified.
- Author/project attribution: Victorious Framework; author Victorious.
- No external publishing, messages, or repository creation is part of this foundation milestone.

## Scope and subsequent plans
The design spans emulator integration, controller UX, network streaming, and hardware transports. This plan deliberately covers the first independently verifiable deliverable: the emulator foundation. Follow-on milestone plans must use the actual checked-out source paths and interfaces established here.

| Milestone | Deliverable and acceptance |
| --- | --- |
| 1 — this plan | Pinned source, license inventory, verified toolchain, source-built APK with native libraries, local runtime report |
| 2 | Game import, dedicated Play screen, full editable N64 touch controls, physical controller remapping, save/export/resume |
| 3 | Same-app Onn receiver, authenticated LAN stream, separate phone Controller mode, disconnect handling, measured latency |
| 4 | Capability detection and supported wired display/IP transports; Wi-Fi Direct and additional receiver platforms evaluated individually |

Milestone 2 must test malicious/ambiguous ZIP imports, canceled file selection, multitouch, rotated/inset displays, lost controllers, and incompatible save states. Milestone 3 must test unpaired commands, expired pairing, LAN discovery failures, network changes, decoder failure, and revocation. Milestone 4 must test charging-only cords and unsupported USB/display hardware. These are future-plan requirements, not features delivered in milestone 1.

## Review Focus
1. Source availability or missing native dependencies: report the blocked dependency precisely rather than replacing it with an unexplained binary.
2. Build success but incomplete APK native payload: inspect every advertised ABI before installation.
3. ABI-compatible installation but renderer failure: distinguish boot/render/audio observations from build success.
4. Accidentally bundling game content: require a ROM-free source tree and APK; import the user's ZIP separately.
5. Existing emulator installation and saves: use a distinct application ID before installing a modified app, and do not overwrite existing app data.

## Proposed files and boundaries
The following documentation and verification files are new, proposed paths. Existing upstream source files are not claimed to have been inspected locally.

- docs/Victorious-Framework-Upstream-Audit.md — pinned source identities, dependency inventory, source layout, licenses.
- docs/Victorious-Framework-Build-Environment.md — exact JDK/SDK/NDK/Gradle versions and reproducible commands.
- docs/Victorious-Framework-APK-Validation.md — artifact digest, ABIs, manifest, installation/runtime evidence.
- scripts/verify_apk_payload.py — validate Android manifest/DEX/native entries and reject bundled game files.
- scripts/tests/test_verify_apk_payload.py — synthetic ZIP fixtures, never commercial ROM data.
- app/build.gradle — application ID/native packaging only after baseline verification and reading inherited build configuration.
- docs/superpowers/specs/2026-10-13-Victorious-Framework-universal-n64-design.md — copy of approved design.
- docs/superpowers/plans/2026-10-13-Victorious-Framework-n64-foundation.md — copy of this plan.

## Task 1: Resolve and pin upstream source
**Files:** Create Upstream-Audit.md and the two design/plan copies above.
**Interfaces:** Produces a checked-out Git commit, complete dependency identities, an audited source-path map, and the verified wrapper/task names consumed by Task 2.

- [ ] Read existing workspace/repository instructions before creating an isolated development checkout. Preserve unrelated files.
- [ ] Fetch https://github.com/mupen64plus-ae/mupen64plus-ae.git into an isolated project directory. Do not commit either supplied ZIP or APK.
- [ ] Record git rev-parse HEAD, git status --short, and git submodule status --recursive. Resolve required submodules from upstream configuration.
- [ ] Read README, build.gradle, settings.gradle or their actual equivalents, gradle/wrapper/gradle-wrapper.properties, app/build.gradle, shared build scripts, native build definitions, and license files.
- [ ] Write the audit with exact paths for ROM loading, gameplay rendering/audio, input dispatch, save-state operations, lifecycle, and TV manifests; identify maintainable extension points.
- [ ] Inventory every required native component's source, pinned revision, license, and build entry point. If a required source cannot be obtained, report the blocker before integration.
- [ ] Check the checkout and documentation for game payloads; exclude commercial ROMs and user saves.
- [ ] Commit only the audit/design/plan documents if the checkout is under Git. No push.

**Acceptance:** An actual pinned revision and verified source/dependency inventory, with no guessed source paths or invented build commands.

## Task 2: Reproduce the unmodified emulator build
**Files:** Create Build-Environment.md; modify local SDK configuration only as required by the inspected checkout.
**Interfaces:** Consumes Task 1's pinned checkout; produces exact toolchain versions, an observed build command, build log, and baseline APK path.

- [ ] Check installed JDK, Android SDK, NDK, disk space, and the Gradle wrapper. Record available/missing prerequisites without printing credentials.
- [ ] Read the upstream CI workflow and resolve toolchain versions from repository declarations. Install required dependencies only through supported sources; do not broadly upgrade the project.
- [ ] Run the existing wrapper's version and task listing. Choose the actual application debug build task from its output.
- [ ] Build the unmodified baseline. On failure, identify the first causal error and fix only the required environment/source compatibility issue, recording deviations.
- [ ] Record successful command, exact artifact path, SHA-256, toolchain versions, native components, and clean rebuild procedure in Build-Environment.md.
- [ ] Commit documentation and any justified build fixes separately from feature changes.

**Acceptance:** Actual successful source compilation, or an honest report of the specific missing prerequisite. No placeholder APK counts.

## Task 3: Verify native payload and ROM-free packaging
**Files:** Create scripts/verify_apk_payload.py and scripts/tests/test_verify_apk_payload.py.
**Interfaces:** Produces verify_apk(path: pathlib.Path, expected_abis: set[str], required_libraries: set[str]) -> list[str]. An empty list means payload checks pass; nonempty strings describe failures. Command-line invocation exits 0 only on pass.

- [ ] Write unittest cases using synthetic APK ZIP fixtures: missing manifest, missing DEX, missing library per ABI, unexpected game file, and valid expected ABI/library layout.
- [ ] Run python3 -m unittest discover -s scripts/tests -v and confirm failures arise from the missing verifier.
- [ ] Implement ZIP entry checks and parser/CLI; reject bundled .z64/.n64/.v64 ROM entries. Verify named native libraries for every supported ABI using Task 1's actual inventory.
- [ ] Run the same tests and require all pass.
- [ ] Execute the verifier against the baseline APK with the exact ABI and library sets recorded by the build. Inspect the manifest with the installed Android SDK's APK analysis tool.
- [ ] Document manifest/application ID, minimum SDK, ABIs, native library coverage, and APK digest in APK-Validation.md.
- [ ] Commit verifier, meaningful tests, and validation documentation.

**Acceptance:** Advertised ABIs have the required native libraries; manifest and DEX exist; no game payload is bundled. This does not assert runtime compatibility.

## Task 4: Isolate app identity and verify local runtime
**Files:** Modify app/build.gradle only where the inspected build defines applicationId; update APK-Validation.md.
**Interfaces:** Produces an independently installable Victorious Framework test APK and a runtime evidence matrix.

- [ ] Locate the final application ID, including build-type suffixes and inherited configuration. Choose org.victoriousframework.n64 with the project's debug suffix, checking for collisions.
- [ ] Inspect authorities, provider names, hardcoded package dependencies, permissions, and signature-bound assumptions before changing ID. Preserve working upstream namespace/internals unless the source requires changes.
- [ ] Rebuild and rerun Task 3 payload verification; confirm the final manifest has the intended distinct package ID.
- [ ] If an authorized compatible Android device is available, install the test APK without uninstalling M64Plus FZ. Import the provided ZIP through the existing UI.
- [ ] Observe game launch, gameplay frames, audio, pause/resume, touch input, and game save persistence. Record exactly what was exercised and any failures.
- [ ] If no device is available, label all runtime checks unverified and deliver the build artifact only if compilation and payload verification actually passed.
- [ ] Record tested device/Android/ABI, renderer, observed results, and limitations. Commit source changes/documentation.

**Acceptance:** APK identity cannot overwrite the existing emulator installation. Runtime claims match actual device observations; no inferred “working” claim from build output.

## Milestone completion and next-plan inputs
Deliver the source archive/checkout, APK if built, build instructions, license attribution, checksums, and evidence report. Preserve user-facing artifacts persistently and provide exact links.
Before milestone 2 planning, use the actual source map to choose reuse versus narrowly scoped new components. Before milestone 3 planning, inspect the renderer/audio hooks to determine whether direct-frame capture and streaming are practical; do not assume a video player or Cast button streams emulator output.
The Onn stick's exact model, API level, CPU ABI, codec support, and network behavior must be observed during receiver testing. Lack of an Onn test device does not prevent foundation development.

## Self-review
Coverage: milestone 1 implements the approved source/core build requirement and establishes packaging/lifecycle evidence. Every remaining design section is assigned to milestones 2–4 with its acceptance conditions.
All source modifications depend on actual Task 1 checkout inspection. Tool versions and task names are discovered rather than fabricated. The five Review Focus risks are exercised by source inventory, payload tests, runtime observations, ROM-free checks, and identity checks.
No gameplay or network feature is claimed implemented.

## Execution handoff
Recommended execution: Native — implement this foundation plan in the current session, retaining one consistent view of the native dependency/toolchain audit. Review this plan before implementation. Subagent-driven execution is an available alternative if explicitly selected.

