# AOSP reconstruction — 2026-11-24
Base: 52fb6dc97b05d3f7f49338384730f854c8840f0e. Only feature branch; no deployment.
| Milestone | Status | Evidence |
|---|---|---|
| M0 | PARTIAL | Remote tree/rules, LFS pointer, extractor, CI and historical boot audit inspected. Local shared checkout unavailable. |
| M1 | IMPLEMENTED / NOT RUN | Read-only image hashes, allowlisted properties, ELF inventory. Requires smudged LFS. |
| M2 | PARTIAL IMPLEMENTATION / NOT TESTED | Five CPU and RAM samples via explicitly selected ADB device. No device in execution environment. |
| M3 | PARTIAL | 38 seed repos, 36 metadata lookups succeeded, two returned 404; usefulness and completeness not verified; see research.md. |
| M4 | NOT FOUND | Exact Android12 RK3328 SDK not confirmed; other Rockchip manifests do not establish completeness. |
| M5–M18 | BLOCKED / NOT TESTED | No verified source tree, product config, build host or live device. |
| M19 | PARTIAL | Separate static and manual self-hosted preflight workflow. |
| M20 | NOT STARTED | No firmware/OTA/release candidate. |
The existing release evidence is not carried forward. No performance claims.

## Second research pass — 2026-11-24
- Previous PR #22 merged externally; Repository CI and AOSP preflight succeeded for prior SHA.
- M3/M4: six source references pinned to commit SHA; RKR12/RKR14 base manifests inspected; original RK3328 Android 12 SDK still not acquired. PINE64 published Android 9 SDK archive listing found, download unverified.
- M5/M6: offline kernel config, manifest, VINTF and ELF inventory comparison implemented; actual stock input and compatibility checks remain BLOCKED.
- M11: Android 9 archive is a credible alternative to investigate after Android 12 baseline.
- No build or physical tests; no release candidate. See kernel.md, architecture.md, platform-comparison.md and compatibility-tools.md.
