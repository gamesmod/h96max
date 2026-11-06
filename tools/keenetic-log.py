#!/usr/bin/env python3
"""Poll Keenetic routers' system log + wifi associations; append new lines to logs/.
Creds: ~/rk3318-android/secrets/keenetic.env (600): one line per router: URL LOGIN PASSWORD"""
import hashlib, http.cookiejar, json, os, time, urllib.request
S = os.path.expanduser('~/rk3318-android/secrets/keenetic.env')
L = os.path.expanduser('~/rk3318-android/logs'); os.makedirs(L, exist_ok=True)
PAT = ('h96', 'rk3318', 'android', '10.12.54.40', 'assoc', 'auth', 'deauth', 'dhcp', 'wpa', 'eapol')

class R:
    def __init__(s, url, login, pw):
        s.url, s.login, s.pw = url.rstrip('/'), login, pw
        s.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        s.seen = set(); s.name = url.split('//')[1].replace(':', '_')
    def auth(s):
        try: s.op.open(s.url + '/auth', timeout=10); return True
        except urllib.error.HTTPError as e:
            if e.code != 401: raise
            realm, ch = e.headers['X-NDM-Realm'], e.headers['X-NDM-Challenge']
        md5 = hashlib.md5(f'{s.login}:{realm}:{s.pw}'.encode()).hexdigest()
        body = json.dumps({'login': s.login, 'password': hashlib.sha256((ch + md5).encode()).hexdigest()}).encode()
        rq = urllib.request.Request(s.url + '/auth', body, {'Content-Type': 'application/json'})
        return s.op.open(rq, timeout=10).status == 200
    def get(s, path):
        try: return json.load(s.op.open(s.url + path, timeout=15))
        except urllib.error.HTTPError as e:
            if e.code == 401 and s.auth(): return json.load(s.op.open(s.url + path, timeout=15))
            raise
    def poll(s):
        log = s.get('/rci/show/log?max-lines=300')
        items = log.get('log', log) if isinstance(log, dict) else log
        items = items.values() if isinstance(items, dict) else items
        with open(f'{L}/{s.name}-all.log', 'a') as fa, open(f'{L}/{s.name}-box.log', 'a') as fb:
            for it in items:
                line = f"{it.get('timestamp','')} {it.get('ident','')}: {it.get('message','')}" if isinstance(it, dict) else str(it)
                if line in s.seen: continue
                s.seen.add(line); fa.write(line + '\n')
                if any(p in line.lower() for p in PAT): fb.write(line + '\n')
        try:
            assoc = s.get('/rci/show/associations')
            with open(f'{L}/{s.name}-assoc.jsonl', 'a') as f: f.write(json.dumps({'t': time.time(), 'a': assoc}) + '\n')
        except Exception: pass

rs = [R(*l.split(None, 2)) for l in open(S) if l.strip() and not l.startswith('#')]
while True:
    for r in rs:
        try: r.poll()
        except Exception as e:
            open(f'{L}/{r.name}-err.log', 'a').write(f'{time.ctime()} {e!r}\n')
    time.sleep(30)
