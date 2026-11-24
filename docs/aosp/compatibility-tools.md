# Offline comparison commands
From repo root, using local copies of inputs; no compile or device required:
```bash
python3 aosp/scripts/compare.py kernel-config stock.config candidate.config
python3 aosp/scripts/compare.py manifest old.xml new.xml
python3 aosp/scripts/compare.py vintf old-manifest.xml new-manifest.xml
python3 aosp/scripts/compare.py blobs old-inventory.json new-inventory.json
./aosp/panadonik.sh test
```
Outputs JSON to stdout, comparisons are static and successful exit does NOT mean compatible. Manifest comparison does not expand includes, extend/remove projects or nested projects; marks unresolved. VINTF inventory does not implement matrix semantics. Blob compare uses previous extractor inventory, hashes, ELF class and needed sets; no linker resolution. Keep real dumps and output private. Unit tests exercise differences and incomplete evidence.
