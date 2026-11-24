# Armbian RK3318 — pinned patch inventory
Commit [73bf6d94d07eb22ce696dfab124d60e716f18488](https://github.com/armbian/build/tree/73bf6d94d07eb22ce696dfab124d60e716f18488).
Observed patch directories:
- [rockchip64-6.12](https://github.com/armbian/build/tree/73bf6d94d07eb22ce696dfab124d60e716f18488/patch/kernel/archive/rockchip64-6.12): rk3328-add-dmc-driver.patch, rk3328-add-rga-node.patch, rk3328-dtsi-mali-opp-table.patch, rk3328-dtsi-spdif.patch, rk3328-dtsi-usb3-reset-properties.patch, rk3328-gpu-cooling-target.patch, general-rk3328-dtsi-trb-ent-quirk.patch.
- [rockchip64-6.18](https://github.com/armbian/build/tree/73bf6d94d07eb22ce696dfab124d60e716f18488/patch/kernel/archive/rockchip64-6.18): DMC, RGA, SPDIF, USB3 reset and inno USB3 PHY patches.
- [U-Boot v2026.04 board patch directory](https://github.com/armbian/build/tree/73bf6d94d07eb22ce696dfab124d60e716f18488/patch/u-boot/v2026.04/board_rk3318-box).
- [rk3318-config](https://github.com/armbian/build/blob/73bf6d94d07eb22ce696dfab124d60e716f18488/packages/bsp/rk3318/rk3318-config) selects among seven LED/board configurations, including alternate SDIO Wi-Fi and RK805 boards; eMMC DDR/HS200 modes optional. This confirms generic rk3318-box DT is not evidence of the blue H96 board wiring.
Priority: investigate USB/PHY/controller DT and role, DMC/VOP, audio, SDIO/pinctrl, eMMC and thermal. Patch filenames verified; implementation/portability of patches not yet verified. No patch application, new loader, OPP or memory timing changes authorized.
