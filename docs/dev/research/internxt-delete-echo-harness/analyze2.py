import json,re,glob,os,statistics as st
from datetime import datetime
def ts(s): return datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def fix(s): return re.sub(r',\}','}',re.sub(r',\]',']',s))
R=[json.loads(fix(l)) for l in open('echo-4.jsonl') if '"ev":"round"' in l]
A=[json.loads(l) for l in open('actions.jsonl')]
# controls
print('controls: a6 listed in rounds up to cleanup:', sum(1 for r in R if 'a6.txt' in r['parents']['pa']) , '/', len(R))
print('controls: b6 listed:', sum(1 for r in R if isinstance(r['parents']['pb'],list) and 'b6' in r['parents']['pb']), '/', len(R))
cl=[a for a in A if a.get('ev')=='cleanup_root'][0]; cl_t=ts(cl['done'])
pre=[r for r in R if ts(r['end'])<cl_t]
print('rounds before cleanup',len(pre),'after',len(R)-len(pre))
print('meta of controls, pre-cleanup: a6', set(r['meta'].get('pa/a6.txt') for r in pre), 'b6', set(r['meta'].get('pb/b6') for r in pre))
# batch per rep
for rep in range(1,6):
    a=[x for x in A if x.get('item')=='c%d'%rep][0]; w=ts(a['done'])
    okf=[f for f in range(1,31) if '"ok":true' in open('rm-c%d-%d.out'%(rep,f)).read()]
    failf=[f for f in range(1,31) if f not in okf]
    post=[r for r in pre if ts(r['end'])>w]
    first=post[0]; 
    lst=first['parents']['pc%d'%rep]
    still=[f for f in okf if 'f%d.txt'%f in lst]
    # feed status of tracked rows in first post round
    fk={x['u']:x for x in first['feed_files']}
    trashed_feed=sum(1 for f in okf if 'pc%d/f%d.txt'%(rep,f) in fk and fk['pc%d/f%d.txt'%(rep,f)]['status']!='EXISTS')
    # failed ones: still listed in last pre round?
    last=pre[-1]['parents']['pc%d'%rep]
    fail_listed=sum(1 for f in failf if 'f%d.txt'%f in last)
    # first round where all ok files are gone from listing
    gone=None; prev=None
    for r in post:
        l=r['parents']['pc%d'%rep]
        if all('f%d.txt'%f not in l for f in okf): gone=(round(ts(prev['end'])-w) if prev else 0, round(ts(r['end'])-w)); break
        prev=r
    print('rep',rep,'ok',len(okf),'failed(daemon_not_answering)',len(failf),'| ok files still listed in 1st post round:',len(still),'| feed TRASHED in 1st post round:',trashed_feed,'/',len(okf),'| all ok gone from listing within',gone,'| failed ones still listed pre-cleanup:',fail_listed,'/',len(failf))
# flaps: feed status regressions and listing reappearance
reg=0; keys={}
for r in pre:
    for x in r['feed_files']+r['feed_folders']:
        keys.setdefault(x['u'],[]).append((x['status'],x['deleted'],x['removed']))
for k,v in keys.items():
    seen_non=False
    for s in v:
        if s[0]!='EXISTS': seen_non=True
        elif seen_non: reg+=1; print('feed regression',k,v[:6])
print('feed regressions',reg,'| tracked keys with feed rows',len(keys),'| max feed rows total (files/folders):',max(r['feed_files_total'] for r in pre),max(r['feed_folders_total'] for r in pre))
# tracked-row hits vs total
print('feed rows total==hits in last pre round:', pre[-1]['feed_files_total'], len(pre[-1]['feed_files']), pre[-1]['feed_folders_total'], len(pre[-1]['feed_folders']))
# trash listing growth / disappearance
cnt=[len(r['trash']) for r in R]; print('trash sizes first/min/max/last',cnt[0],min(cnt),max(cnt),cnt[-1])
# status of folder b feed rows
print('b folder feed statuses (last pre round):',[ (x['u'],x['status'],x['deleted']) for x in pre[-1]['feed_folders']])
print('b meta last pre:',{k:v for k,v in pre[-1]['meta'].items() if k.startswith('pb/')})
print('b1 children probe: n/a (not tracked)')
# trash names after cleanup
last=R[-1]; names={}
for t in last['trash']:
    m=re.search(r'id=([^,]+), isFolder=(\w+), name=([^,]+),',t); names.setdefault(m.group(3),[]).append((m.group(1),m.group(2)))
mine=[n for n in names if n.startswith('_ud-spike-ledger') or re.fullmatch(r'(a[1-5]|f[0-9]+)\.txt|b[1-5]|s0',n)]
print('trash total',len(last['trash']),'mine-by-name',{n:len(names[n]) for n in mine})
print('root in trash:',names.get('_ud-spike-ledger-20261127-1055'))
others=[t for t in last['trash'] if not re.search(r'name=(_ud-spike|a[1-5]\.txt|b[1-5],|f[0-9]+\.txt|s0,)',t)]
print('other trash items:',len(others)); [print('  ',re.sub(r'sizeBytes=\d+, ','',o)) for o in others]
# post-cleanup listing errors
print('post-cleanup parent listing sample:', {k:(v if isinstance(v,str) else len(v)) for k,v in R[-1]['parents'].items()})
# per-source intervals summary (a,b)
