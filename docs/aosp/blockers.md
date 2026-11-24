# Technical blockers
- Execution sandbox: 5 logical CPUs, 5.8 GiB RAM, ~30 GB free; git ls-remote failed DNS; adb/repo unavailable. No physical device exposed. Full AOSP checkout/compile and smudging stock binary LFS not performed.
- Repository API returns LFS system.img pointer (921714688 bytes, SHA-256 384cc259e8eb1e47751f5be4b5cda49997526c3e222b88a8d1dea4df967c78bd), not image bytes.
- No verified RK3328 Android12 SDK or target product configuration. No source tree or verified manifest lock. Do not compile unrelated product and call it Panadonik.
- Need networked Ubuntu x86_64 build host, locally available stock images, device ADB read-only access, and legal clearance per blob. For full build, provision existing resources; do not create paid resources.
- Benchmark measures aggregate CPU busy percentage and available memory only; no boot, GPU/VPU, UI, thermal or stability conclusion.
