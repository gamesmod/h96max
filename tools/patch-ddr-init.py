#!/usr/bin/env python3
"""Use the real system-monitor attribute: Android init write rejects final symlinks."""
import pathlib
import re
import sys

OLD = '/sys/devices/platform/dmc/devfreq/dmc/system_status'
NEW = '/sys/system_monitor/system_status'


def patch(text):
    # Require both vendor actions, and reject ambiguous/changed vendor inputs.
    for value in ('p', 'n'):
        pattern = rf'(?m)^(\s*write )({re.escape(OLD)}|{re.escape(NEW)})( {value}[ \t]*)$'
        text, count = re.subn(pattern, lambda m: m[1] + NEW + m[3], text)
        if count != 1:
            raise ValueError(f'Expected exactly one DDR system_status {value} action')
    return text


if __name__ == '__main__':
    path = pathlib.Path(sys.argv[1])
    original = path.read_text()
    updated = patch(original)
    if updated != original:
        path.write_text(updated)
