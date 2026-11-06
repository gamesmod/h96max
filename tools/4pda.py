#!/usr/bin/env python3
# usage: 4pda.py PID [PID...]  -> prints text of findpost posts (topic 952710 or any via findpost)
import re,html,sys,subprocess
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'
for arg in sys.argv[1:]:
    topic,pid=(arg.split(':') if ':' in arg else ('952710',arg))
    url=f'https://4pda.to/forum/index.php?showtopic={topic}&view=findpost&p={pid}'
    s=subprocess.run(['curl','-sL','-A',UA,url],capture_output=True).stdout.decode('cp1251','replace')
    i=s.find(f'post-main-{pid}"')
    seg=s[i:]; j=seg.find('post-main-',30); seg=seg[:j] if j>0 else seg[:60000]
    t=re.sub(r'<br ?/?>','\n',seg)
    t=re.sub(r'<a [^>]*href="([^"]+)"[^>]*>(.*?)</a>',r'\2 [\1]',t,flags=re.S)
    t=re.sub(r'<[^>]+>','',t); t=html.unescape(t)
    print(f'===== {pid} =====\n'+t.strip()[:6000])
