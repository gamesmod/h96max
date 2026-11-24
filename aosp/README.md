# Panadonik AOSP — stage 0/1
On existing Ubuntu host, from repository root:
```bash
./aosp/panadonik.sh test
./aosp/panadonik.sh doctor
./aosp/panadonik.sh research
./aosp/panadonik.sh extract --work "$HOME/panadonik-private/stock-001"
adb devices -l
./aosp/panadonik.sh benchmark --serial SERIAL --work "$HOME/panadonik-private/baseline-001"
```
Use the existing LFS inputs (never overwrite stock). Keep work directories private. Research accepts an optional local manifest path; it reports unresolved includes and pinning, not complete compatibility. HTTP failures remain unverified. Exit 3 is BLOCKED. Extract uses the existing debugfs dump wrapper; partial extraction and licenses need additional review. No generated blobs are approved for distribution. Benchmark is non-invasive; confirm idle conditions separately.
Sync/configure/kernel/build/package/verify intentionally exit BLOCKED until an actual source/target configuration is validated. No flash, OTA, signing or publication. See docs/aosp/.
