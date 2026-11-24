# Platform selection — initial source evidence
No weighted numeric score until measured. Hardware and safety gates remain mandatory.

| Candidate | Evidence | Decision |
|---|---|---|
| A Android 12 stock BSP | Rockchip 4.19.232 candidate, RKR12 common configuration | first reconstruction, no stock system fallback |
| B optimized Android 12 | depends on A baseline | defer optimizations |
| C Android 11 BOX | named in vendor guide | investigate access; no verified source |
| D Android 10 BOX | named in vendor guide | investigate access; no verified source |
| E Android 9 BOX | PINE64 official archive listing 104.34 GB and published MD5; historical Git mirror unavailable | credible alternative, archive not fetched or checked |
| F newer kernel and HAL | 4.19 source candidate and mainline references | only after baseline |
| G mainline/Lima | Linux board evidence; Android HWC/codec integration unproven | separate research, no replacement now |

Sources: [Rockchip SDK catalog](https://lo01.g77k.com/aeb/docs/en/Others/Rockchip_User_Guide_SDK_Application_And_Synchronization_EN.pdf), [2022 Android12 guide](https://nnewn2021.site1.hwcloudsite.cn/upload/Rockchip_Android12_SDK_Developer_Guide_EN_7jmn.pdf), [PINE64 ROCK64 releases](https://pine64.org/documentation/ROCK64/Software/Releases/#android-sdk), [ROCK64 Android 9 discussion](https://forum.pine64.org/showthread.php?tid=7595).
The 2022 guide revision predates stock and does not list RK3328; later catalog does list it. The guide documents authenticated SDK acquisition; no access bypass. Ask manufacturer/FAE through lawful channels if needed. PINE64 archive completeness, current availability and distribution terms not verified; forum reports Ethernet fix, Wi-Fi not confirmed.
