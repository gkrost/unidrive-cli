import json,re,sys,statistics as st
from datetime import datetime
def ts(s): return datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def fix(s): return re.sub(r',\}','}',re.sub(r',\]',']',s))
rounds=[];acts=[]
for l in open('echo-4.jsonl'):
    d=json.loads(fix(l))
    if d.get('ev')=='round': rounds.append(d)
for l in open('actions.jsonl'):
    d=json.loads(l)
    if d.get('item'): acts.append(d)
def item_obs(r,it,case):
    """returns dict of booleans: present_in_parent, in_trash, feed_status, meta"""
    o={}
    if case=='a': par,name='pa',it+'.txt'
    elif case=='b': par,name='pb',it
    elif case=='e': par,name='pe',it+'.txt'
    o['listed']= name in r['parents'][par] if isinstance(r['parents'][par],list) else None
    o['trash']= any(('name='+name+',') in t or ('name='+name+')') in t for t in r['trash'])
    key={'a':'pa/','b':'pb/','e':'pe/'}[case]+name
    fs=[x for x in r['feed_files']+r['feed_folders'] if x['u']==key]
    o['feed']= fs[0]['status']+('/d' if fs[0]['deleted'] else '')+('/r' if fs[0]['removed'] else '') if fs else 'absent'
    o['meta']=r['meta'].get(key)
    return o
def rel(t,w): return round(ts(t)-w,0)
out=[]
for a in acts:
    c=a['case']
    if c=='c': continue
    w=ts(a['done']); obs=[(r,item_obs(r,a['item'],c)) for r in rounds]
    # only a round that STARTED after the write returned is evidence about the state after it; a round that straddled the write
    # (started before `done`) is dropped, so a lower bound is never taken from a listing read before the delete was sent
    post=[(r,o) for r,o in obs if ts(r['start'])>w]
    def first(pred):
        prev=None
        for r,o in post:
            if pred(o): return (round(ts(prev['end'])-w) if prev else 0, round(ts(r['end'])-w))
            prev=r
        return None
    row={'item':a['item'],'case':c,'write_ms':round((ts(a['done'])-ts(a['sent']))*1000)}
    row['unlisted_in_parent']=first(lambda o:o['listed'] is False)
    row['in_trash']=first(lambda o:o['trash'])
    row['meta_not_exists']=first(lambda o:o['meta'] and o['meta'].split('/')[0] in ('TRASHED','DELETED'))
    row['feed_non_exists']=first(lambda o:o['feed'].split('/')[0] in ('TRASHED','DELETED'))
    row['feed_any_row']=first(lambda o:o['feed']!='absent')
    # reversals: listed again / trash vanished / meta back to EXISTS after first change
    rev=0; seen=False
    for r,o in post:
        gone=(o['listed'] is False)
        if gone: seen=True
        elif seen: rev+=1
    row['reversals_listed']=rev
    row['last_round_sec']=round(ts(rounds[-1]['end'])-w)
    out.append(row)
json.dump(out,open('analysis-items.json','w'),indent=1)
for r in out: print(r)
# batch
for a in acts:
    if a['case']=='c': print({k:a[k] for k in('item','ok','trash_add_calls','single_meta_calls')}, round(ts(a['done'])-ts(a['sent'])))
# children of pc reps: gone from listing
for rep in range(1,6):
    w=[ts(a['done']) for a in acts if a['item']=='c%d'%rep]
    if not w: continue
    w=w[0]; prev=None
    for r in rounds:
        if ts(r['end'])<=w: continue
        lst=r['parents']['pc%d'%rep]
        if isinstance(lst,list) and len(lst)==0:
            print('pc%d empty listing first seen between +%ds and +%ds'%(rep, round(ts(prev['end'])-w) if prev else 0, round(ts(r['end'])-w))); break
        prev=r
    else: print('pc%d still listed after run'%rep, len(rounds[-1]['parents']['pc%d'%rep]) if isinstance(rounds[-1]['parents']['pc%d'%rep],list) else rounds[-1]['parents']['pc%d'%rep])
errs={'meta':0,'listing':0,'trash':0,'feed':0}
for r in rounds:
    errs['meta']+=sum(1 for v in r['meta'].values() if str(v).startswith('ERR'))
    errs['listing']+=sum(1 for v in r['parents'].values() if isinstance(v,str))
    errs['trash']+= 1 if isinstance(r['trash'],str) else 0
    errs['feed']+= 1 if (r.get('feed_files_err') or r.get('feed_folders_err')) else 0
print('read errors per source over all rounds:',errs)
print('rounds',len(rounds))
