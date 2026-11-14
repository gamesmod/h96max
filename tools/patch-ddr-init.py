#!/usr/bin/env python3
"""Use the real system-monitor attribute: Android init write rejects final symlinks."""
import pathlib
import re
import sys

OLD = '/sys/devices/platform/dmc/devfreq/dmc/system_status'
OLD_PERMISSIONS = '/sys/class/devfreq/dmc/system_status'
NEW = '/sys/system_monitor/system_status'


def patch(text):
    # Require both vendor actions, and reject ambiguous/changed vendor inputs.
    for value in ('p', 'n'):
        pattern = rf'(?m)^([ \t]*write )({re.escape(OLD)}|{re.escape(NEW)})( {value}[ \t]*)$'
        text, count = re.subn(pattern, lambda m: m[1] + NEW + m[3], text)
        if count != 1:
            raise ValueError(f'Expected exactly one DDR system_status {value} action')
    # init chown/chmod act on the sysfs alias rather than its destination.
    # Keep the vendor's intended owner/mode on the actual writable attribute.
    permissions = (('chown system system', 1), ('chown mediacodec system', 1), ('chmod 0660', 2))
    aliases = '|'.join(re.escape(p) for p in (OLD, OLD_PERMISSIONS, NEW))
    for action, expected in permissions:
        pattern = rf'(?m)^([ \t]*{re.escape(action)}[ \t]+)(?:{aliases})([ \t]*)$'
        text, count = re.subn(pattern, lambda m: m[1] + NEW + m[2], text)
        if count != expected:
            raise ValueError(f'Unexpected DDR permission actions: {action}')
    allowed = {f'write {NEW} {value}' for value in ('p', 'n')}
    allowed.update(f'{action} {NEW}' for action, _ in permissions)
    for line in text.splitlines():
        command = ' '.join(line.split('#', 1)[0].split())
        if any(p in command for p in (OLD, OLD_PERMISSIONS, NEW)) and command not in allowed:
            raise ValueError('Unexpected DDR system_status action')
    return text


if __name__ == '__main__':
    path = pathlib.Path(sys.argv[1])
    original = path.read_text()
    updated = patch(original)
    if updated != original:
        path.write_text(updated)
