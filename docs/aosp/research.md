# Initial research
Observed 2026-11-24 via GitHub metadata and public documentation. Seed registry is not 38 verified useful projects.
| Repository | Observed default branch / finding |
|---|---|
| a9rock64/manifests | API 404; [historical Android 9 forum](https://forum.pine64.org/showthread.php?tid=7595) |
| rock64-android/manifests | default; RK3328 Nougat release/mirror XML, not 12 |
| rockchip-android/device-rockchip-rk3328 | rk3328-box-7.1; product/device/BoardConfig/fstab present |
| android-rockchip/build | master; Android12_rkr12_base/custom.xml, Android12_rkr14_base/custom.xml, rockchip-s-release.xml |
| TinkerBoard-Android/rockchip-android-manifest | main; README only on default branch |
| TinkerBoard-Android/rockchip-android-device-rockchip-rksdk | android12-rockchip; common device.mk branches select gralloc and MPP buffer type |
| radxa/manifests | rockpi-box-9.0; [Android12_Radxa_rk14 instructions](https://docs.radxa.com/en/som/cm/cm5/android/low-level-dev) concern CM5, not proof of RK3328 |
| armbian/build | main; rk3318-box.tvb; [historical DTS reference](https://forum.armbian.com/topic/26978-csc-armbian-for-rk3318rk3328-tv-box-boards/) |
| paolosabatino/armbian-build | main; historical rk3318 patches require commit recovery |
| jphein/rk3318-tvbox-kvm | main; diagnostics only |
| Moxnatiy/rk3318-box-revival | master; diagnostics only, no hardware modifications |
| rockchip-linux/kernel | develop-6.1; not exact stock |
| friendlyarm/kernel-rockchip | nanopi6-v6.1.y; not exact stock |
Exact RK3328_ANDROID12.0_SDK_RELEASE not confirmed accessible. SDK 9 lead historical only; SDK 10/11 unverified. Full Git/manifest/SHA/license inventory requires networked host. The research script obtains default-branch commit metadata, not legal clearance, not recursive manifest resolution and not a compatibility verdict.
Additional sources: https://android.googlesource.com/platform/manifest/ ; https://android.googlesource.com/device/google/atv/ ; https://docs.mesa3d.org/drivers/lima.html ; https://wiki.t-firefly.com/en/Core-3328-JD4/android_compile_android8.html .
