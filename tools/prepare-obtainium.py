#!/usr/bin/env python3
"""Prepare Obtainium v1.7.4 app_data records; never invent checked versions."""
import json
from pathlib import Path
import re
import sys


def prepare(source, output):
    apps = json.loads(Path(source).read_text())
    records = {}
    for app in apps:
        package = app['id']
        if not re.fullmatch(r'[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+', package):
            raise ValueError('Invalid package ID')
        if package in records:
            raise ValueError('Duplicate package ID: ' + package)
        settings = json.loads(app.get('additionalSettings', '{}'))
        if not isinstance(settings, dict) or not app['url'].startswith('https://'):
            raise ValueError('Invalid source: ' + package)
        if settings.get('apkFilterRegEx'):
            re.compile(settings['apkFilterRegEx'])
        records[package] = dict(app, additionalSettings=json.dumps(settings),
                                installedVersion=None, lastUpdateCheck=None,
                                apkUrls='[["placeholder","placeholder"]]')
    required = Path(__file__).with_name('required-apps.sh').read_text()
    packages = re.findall(r'^    ([\w.]+)\.apk$', required, re.M)
    missing = set(packages) - records.keys()
    if missing:
        raise ValueError('Missing Obtainium sources: ' + ', '.join(sorted(missing)))
    dest = Path(output)
    dest.mkdir(parents=True, exist_ok=True)
    for package, record in records.items():
        (dest / (package + '.json')).write_text(json.dumps(record, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    prepare(*sys.argv[1:])
