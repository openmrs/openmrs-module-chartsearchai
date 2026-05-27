#!/usr/bin/env python3
"""Build the honest-metric gold from this session's adjudicated focus labels.
Gold[patientUuid|topic] = {present: bool, ontopic: {uuid: text}}  (recall anchor + in-focus precision labels)
ontopic UUIDs come from focus_labels.json on-topic RANKS mapped through the focus dumps.
Out-of-focus cited records get adjudicated later and merged (expanding gold)."""
import json, glob, os
Q2T={'Is the patient enrolled in any programs?':'programs','Does the patient have any allergies?':'allergies',
     'What medications is the patient taking?':'medications','Does the patient have any eye problems?':'eye',
     'Does the patient have any heart or cardiac problems?':'heart','Has the patient had any fractures or broken bones?':'fractures',
     'Does the patient have any kidney problems?':'kidney','Does the patient have any mental health or psychiatric conditions?':'mental'}
labels=json.load(open('/tmp/focus_labels.json'))
# index focus dumps by (patientUuid, topic) -> {rank: (uuid,text)}
dumps={}
for p in glob.glob('/tmp/focusdumps2/*.json'):
    d=json.load(open(p)); key=(d['patient'],Q2T[d['question']])
    dumps[key]={h['rank']:(h['uuid'],h.get('text','')) for h in d['hits']}
gold={}
for cellkey,lab in labels.items():
    if cellkey.startswith('_'): continue
    uuid,topic=cellkey.split('|')
    rank2=dumps.get((uuid,topic),{})
    ontopic={}
    for r in lab.get('ranks',[]):
        if r in rank2: ontopic[rank2[r][0]]=rank2[r][1]
    gold[cellkey]={'present':lab['present'],'ontopic':ontopic}
json.dump(gold,open('/tmp/metric_gold.json','w'),indent=1)
present=[k for k,v in gold.items() if v['present']]
print('gold cells:',len(gold),' present:',len(present),' absent:',len(gold)-len(present))
print('on-topic UUID counts per present cell:')
for k,v in sorted(gold.items()):
    if v['present']: print('  %-55s %d'%(k.split('|')[1]+' ('+k[:8]+')',len(v['ontopic'])))
