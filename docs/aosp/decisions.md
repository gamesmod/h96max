# ADR-001 — preserve the working BSP before optimization
Status: accepted engineering direction, not validated implementation.
The repository builds from cleaned stock LP images; the new product must compile Framework/system from source. Keep the existing kernel 4.19.232, DT, boot chain, firmware and vendor stack together until compatibility is proved. Compare VNDK/linker namespaces, VINTF and ELF dependencies before changing any HAL.
The initial tool is read-only and fails closed for unimplemented build/packaging operations. No speculative BoardConfig sizes, product ABI, fstab, SELinux exceptions or SDK tag. No stock-system fallback.
Reference: docs/release-qa/boot-audit-2026-11-23-followup.md — historical 0.4.10 observed boot, UDC missing, devfreq/VOP/DMC/HEVC warnings unresolved. Kernel sha256 aa188f6e71a8e5490f484c56f7f9db3a3e0a5dadd1bc2567cd47ae77eacf305c; source correspondence unknown.
AOSP host preflight uses conservative 400 GB / 64 GB no-swap policy; reference https://source.android.com/docs/setup/start/requirements . This is a policy for full source builds, not proven minimum for this BSP.
