# Kernel 4.19.232 — evidence 2026-11-24

Primary candidate: [Rockchip develop-4.19](https://github.com/rockchip-linux/kernel/tree/f39b590ea11da2d897644e42119ba5099b2eefc8), commit dated 2022-08-04. Makefile confirms 4.19.232. Secondary: [FriendlyELEC nanopi4-v4.19.y](https://github.com/friendlyarm/kernel-rockchip/tree/758118c9a424038bfb5fef63bcac0d2dd9c67a84), same version, distinct board.

The stock binary is dated 2022-11-17; version match does not prove OEM source identity. Existing boot audit identifies the kernel hash. Preserve it at first boot.

| Observed symptom | Source evidence | Diagnostic interpretation |
|---|---|---|
| Frequency transition statistics warning | [devfreq.c:364](https://github.com/rockchip-linux/kernel/blob/f39b590ea11da2d897644e42119ba5099b2eefc8/drivers/devfreq/devfreq.c#L364) | The warning occurs after a successful target callback when statistics update fails; not direct proof of failed frequency switching. Compare frequency table, previous/current rate and return values. |
| VOP bandwidth to DMC warning | [rockchip_dmc.c:2912](https://github.com/rockchip-linux/kernel/blob/f39b590ea11da2d897644e42119ba5099b2eefc8/drivers/devfreq/rockchip_dmc.c#L2912) | DT parse failure for vop-bw-dmc-freq. Need exact stock DT and parser result; do not invent table entries. |
| Missing UDC | [rk3328.dtsi](https://github.com/rockchip-linux/kernel/blob/f39b590ea11da2d897644e42119ba5099b2eefc8/arch/arm64/boot/dts/rockchip/rk3328.dtsi) | Compare OTG role, enabled PHY/controller, configfs/gadget config, probe errors and physical port. No USB changes without fallback. |
| Missing clk_hevc_cabac | Historical audit only | Locate exact media driver and clocks before any patch. Hardware HEVC is not verified. |

[rockchip_defconfig](https://github.com/rockchip-linux/kernel/blob/f39b590ea11da2d897644e42119ba5099b2eefc8/arch/arm64/configs/rockchip_defconfig) matches documented stock symbols IKCONFIG_PROC, KALLSYMS_ALL, PM_DEVFREQ, MALI_DEVFREQ, ARM_ROCKCHIP_DMC_DEVFREQ and ARM_ROCKCHIP_DMC_DEBUG. Not sufficient for complete config match. Compare generated .config, not merely sparse defconfig. No voltage, clocks or thermal changes.

Armbian [pinned board](https://github.com/armbian/build/blob/73bf6d94d07eb22ce696dfab124d60e716f18488/config/boards/rk3318-box.tvb) selects rockchip64 family, rk3318-box DT and rk3328 boot SoC. Its Linux boot chain is not authorized for target device.
