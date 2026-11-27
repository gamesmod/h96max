# BCM4334 / bcmdhd research notes (2026-11-27)

## Sources collected
- BCM4334 datasheet page: https://datasheet4u.com/datasheets/Cypress/BCM4334/1257382
  - BCM4334X single-chip: IEEE 802.11 a/b/g/n single-stream, Bluetooth 4.0 + HS, FM receiver; external 2.4/5 GHz FEM; SDIO v2.0/gSPI or HSIC.
- Rockchip bcmdhd develop-4.4:
  - dhd_config.c: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/dhd_config.c
    - chip_name_map: {BCM4334_CHIP_ID, 3, DONT_CARE, FALSE, "bcm4334b1_ag", ""}
    - dhd_conf_preinit default for BCM4334 (else branch): ccode=CN, rev=0.
    - country_list parsing: `country_list=US:US/0, TW:TW/1`; `ccode=` and `regrev=` parsed.
    - dhd_conf_fix_country: if WLC_GET_VALID_CHANNELS fails or 2.4G channels < 11 -> map country_list / set default; if set fails -> US/0.
  - dhd_custom_gpio.c: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/dhd_custom_gpio.c
    - translate_custom_table under CUSTOMER_HW2: AE/1 AR/1 AT/1 AU/2 BE/1 BG/1 BN/1 CA/2 CH/1 CY/1 CZ/1 DE/3 DK/1 EE/1 ES/1 FI/1 FR/1 GB/1 GR/1 HR/1 HU/1 IE/1 IS/1 IT/1 ID/1 JP/8 KR/24 KW/1 LI/1 LT/1 LU/1 LV/1 MA/1 MT/1 MX/1 NL/1 NO/1 PL/1 PT/1 PY/1 RO/1 SE/1 SI/1 SK/1 TR/7 TW/1; IR/SD/SY/GL/PS/TL/MH -> XZ/11; RU/1 and US/5 only under #ifdef BCM4330_CHIP.
  - wldev_common.c: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/wldev_common.c
    - wldev_set_country: get current "country"; copy country_code to country_abbrev and ccode; map country_list; else dhd_get_customized_country_code; set "country" iovar; dhd_conf_fix_country; get current; log.
  - wl_cfg80211.c: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/wl_cfg80211.c
    - reg_notifier only under CONFIG_CFG80211_INTERNAL_REGDB; accepts USER and COUNTRY_IE initiators; wldev_set_country.
  - dhd_common.c: dhd_check_current_clm_data: ccode "#n" => no CLM data; dhd_apply_default_clm downloads CLM blob if present.
  - wlioctl.h: WLC_CNTRY_BUF_SZ 4; wl_country_t {country_abbrev[4], rev, ccode[4]}; wl_country_list_t.
- Old AOSP bcmdhd:
  - https://raw.githubusercontent.com/aosp-mirror/kernel_common/bcmdhd-3.10/drivers/net/wireless/bcmdhd/dhd_custom_gpio.c
    - same table; EXAMPLE_TABLE (not default) has US/69, CA->US/69, EU/5, etc.
- Android:
  - WifiCountryCode.java main: https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiCountryCode.java
    - BOOT_DEFAULT_WIFI_COUNTRY_CODE = "ro.boot.wificountrycode"
    - config store WIFI_DEFAULT_COUNTRY_CODE
    - mWorldModeCountryCode = config_wifiDriverWorldModeCountryCode
    - pickCountryCode: override > telephony > driver (if useDriverCountryCodeIfAvailable) > framework scan-derived > config store default
    - setOverrideCountryCode("00") maps to mWorldModeCountryCode
    - isValid: 2 chars alphanumeric only
    - setCountryCodeNative -> ConcreteClientModeManager.setCountryCode; HAL 1.5+ uses IWifiChip.setCountryCode; older uses supplicant.
  - WifiNative.java main: setStaCountryCode -> mSupplicantStaIfaceHal.setCountryCode; old aosp/wifi branch: `DRIVER COUNTRY <XX>`.
  - IWifiChip.aidl: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/wifi/aidl/android/hardware/wifi/IWifiChip.aidl
    - setCountryCode(in byte[2] code); 2-byte ISO 3166; global chip setting.
  - IWifiChip.hal 1.5: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/wifi/1.5/IWifiChip.hal
    - setCountryCode(int8_t[2]); global; legacy supplicant/AP country APIs deprecated in favor.
  - Broadcom wifi_hal.cpp: https://android.googlesource.com/platform/hardware/broadcom/wlan/+/master/bcmdhd/wifi_hal/wifi_hal.cpp
    - SetCountryCodeCommand sends nl80211 vendor command GOOGLE_OUI/WIFI_SUBCMD_SET_COUNTRY_CODE with ANDR_WIFI_ATTRIBUTE_COUNTRY; wifi_set_country_code -> requestResponse.
  - Wi-Fi HAL docs: https://source.android.com/docs/core/connect/wifi-hal
- Firmware binary analysis (local files in /home/ubuntu/research_bcm4334/fw):
  - rk_fw_bcm4334b1_ag.bin (Radxa rkwifibt): 457249 bytes, CLM 1.6.5, table 50 entries incl EU (49 country codes), no BR; US/46.
    URL: https://raw.githubusercontent.com/radxa/rkwifibt/master/firmware/broadcom/all/wifi/fw_bcm4334b1_ag.bin
    Table: EU/0 AE/1 AR/1 AT/1 AU/2 BE/1 BG/1 BN/1 CA/2 CH/1 CY/1 CZ/1 DE/3 DK/1 EE/1 ES/1 FI/1 FR/1 GB/1 GR/1 HR/1 HU/1 IE/1 IS/1 IT/1 JP/5 KR/24 KW/1 LI/1 LT/1 LU/1 LV/1 MA/1 MT/1 MX/1 NL/1 NO/1 PL/1 PT/1 PY/1 RO/1 RU/5 SE/1 SG/4 SI/1 SK/1 TR/7 TW/2 UA/255 US/46
  - armbian_brcmfmac4334-sdio.bin: 398010 bytes, CLM 1.21.1, table 51 entries incl EU (50 country codes), includes BR/4; same others.
    URL: https://raw.githubusercontent.com/armbian/firmware/master/brcm/brcmfmac4334-sdio.bin
  - Samsung bcmdhd_sta.bin_b2 / apsta.bin_b2: CLM 1.6.5, same 50-entry table as Rockchip.
    URLs: https://raw.githubusercontent.com/KashaMalaga/proprietary_vendor_samsung/jellybean/n7100/proprietary/system/etc/wifi/bcmdhd_sta.bin_b2 ; .../bcmdhd_apsta.bin_b2
  - Samsung mfg firmware bcmdhd_mfg.bin_b2: no full CLM table found (best 1 US/0); CLM offset 308968, ClmImport 1.6.5? (not verified).
- NVRAM:
  - Armbian RK3318: https://raw.githubusercontent.com/armbian/firmware/master/brcm/brcmfmac4334-sdio.rockchip,rk3318-box.txt
    - manfid=0x2d0 prodid=0x05c1 vendid=0x14e4 devid=0x4380 boardtype=0x05de boardrev=0x1200; ccode=HK; no regrev.
    - comment: original Android nvram_HS2734A.txt; modified from Samsung N7100 Murata NVRAM; btc_params80=0, btc_params6=10, btc_params95=53.
  - Samsung N7100 production: https://raw.githubusercontent.com/KashaMalaga/proprietary_vendor_samsung/jellybean/n7100/proprietary/system/etc/wifi/nvram_net.txt
    - ccode=GB; same IDs.
  - Samsung N7100 mfg: .../nvram_mfg.txt
    - ccode=ALL; same IDs.
  - Samsung N7100 Murata mfg: .../nvram_mfg.txt_murata
    - ccode=ALL.
- Bluetooth:
  - BCM4334 datasheet page: BT 4.0 + HS, FM, dual-band.
  - BCM4334B0.hcd: https://raw.githubusercontent.com/armbian/firmware/master/brcm/BCM4334B0.hcd (42485 bytes).
  - BCM43342.hcd: https://raw.githubusercontent.com/armbian/firmware/master/brcm/BCM43342.hcd (50924 bytes).
  - Linux btbcm.c: https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/drivers/bluetooth/btbcm.c
  - Android Broadcom libbt: https://android.googlesource.com/platform/hardware/broadcom/libbt/
  - Android Bluetooth stack: https://android.googlesource.com/platform/packages/modules/Bluetooth/
  - Android Bluetooth HAL: https://source.android.com/docs/core/connect/bluetooth
  - No country code parameter in standard Android Bluetooth API; BCM4334 HCD is a firmware patch, not a regulatory database.
- Regulatory:
  - FCC 47 CFR 15.247: https://www.ecfr.gov/current/title-47/chapter-I/subchapter-A/part-15/subpart-C/section-15.247
  - FCC 47 CFR 15.407: https://www.ecfr.gov/current/title-47/chapter-I/subchapter-A/part-15/subpart-E/section-15.407
  - ETSI EN 300 328: https://www.etsi.org/deliver/etsi_en/300300_300399/300328/
  - ETSI EN 301 893: https://www.etsi.org/deliver/etsi_en/301800_301899/301893/
  - ISO 3166-1: https://www.iso.org/iso-3166-country-codes.html
  - Bluetooth SIG Core Spec 4.0: https://www.bluetooth.com/specifications/specs/core-specification-4-0/
  - Bluetooth SIG regulatory: https://www.bluetooth.com/learn-about-bluetooth/regulatory/
- Infineon WHD:
  - whd_types.h docs: https://infineon.github.io/wifi-host-driver/html/whd__types_8h.html
    - 237 WHD_COUNTRY_* enum values; broad enum, not proof for BCM4334.
  - whd-bsp-integration: https://github.com/Infineon/whd-bsp-integration
    - CY_WIFI_COUNTRY; legal options in whd_country_code_t; CLM/module-specific.

## Key conclusions
- BCM4334 firmware CLM table (1.6.5) explicitly lists 49 country codes + EU/0. Newer 1.21.1 adds BR/4 (50 + EU/0).
- Codes absent from CLM 1.6.5: CN, HK, BR, IN, ID, MY, PH, TH, VN, NZ, ZA, SA, IL, EG, etc. ALL is special, not a country.
- `ccode=HK` in Armbian NVRAM is not in CLM table -> unconfirmed/possibly unsupported.
- `ccode=GB` in Samsung NVRAM is in CLM table -> consistent.
- `ccode=ALL` in Samsung mfg NVRAM is special world code -> not a country; not recommended.
- Rockchip bcmdhd default for BCM4334 is CN/0, not in CLM table -> contradiction; actual behavior must be tested.
- Driver table maps some unsupported countries to XZ/11 (IR, SD, SY, GL, PS, TL, MH); US/RU only under BCM4330_CHIP in that table.
- Android sends 2-byte codes without checking firmware support; failure appears in driver logs; Android may keep reporting requested/driver code.
- Android priority: override > SIM/telephony > 802.11d driver > scan-derived > config store default; `ro.boot.wificountrycode` OEM default; `00` maps to world mode.
- H96 Max model does not prove BCM4334; must verify SDIO IDs, dmesg, firmware/NVRAM paths.

## Local files
- /home/ubuntu/research_bcm4334/src: bcmdhd sources (rk_*, aosp_*), Android sources.
- /home/ubuntu/research_bcm4334/fw: firmware/NVRAM variants.
