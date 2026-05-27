#!/usr/bin/env python3
"""Honest-metric scorer. UUID set comparison vs human gold (no model-derived drift terms).
Usage: metric_score.py <capture_dir> [offtopic_adjudications.json]
  capture_dir: per-cell response JSON {answer, references:[{resourceUuid,index,resourceType}]}
  offtopic file (optional): {"<patientUuid|topic>": ["uuid", ...]}  out-of-focus cited records adjudicated OFF-topic
                            and {"_ontopic": {"<cell>": ["uuid",...]}} for out-of-focus adjudicated ON-topic
Classifies each cited record: ON-topic (gold.ontopic + adjudicated-ontopic) / OFF-topic
(in focus but not on-topic, OR adjudicated off) / UNKNOWN (out-of-focus, not yet adjudicated).
Prints per-cell P/R/F1 + abstention, aggregate, and the UNKNOWNS to adjudicate."""
import json, sys, glob, os
Q2T={'Is the patient enrolled in any programs?':'programs','Does the patient have any allergies?':'allergies',
     'What medications is the patient taking?':'medications','Does the patient have any eye problems?':'eye',
     'Does the patient have any heart or cardiac problems?':'heart','Has the patient had any fractures or broken bones?':'fractures',
     'Does the patient have any kidney problems?':'kidney','Does the patient have any mental health or psychiatric conditions?':'mental'}
PN={'4acc0b80-83c4-40f7-86fd-0e11a68dd405':'betty','07e26b8e-00a9-4b31-b805-3560ad4e9e2e':'richard','be83f269-66bd-4ba1-80ec-cc62d0d0c84e':'karen','61d0a9db-d35f-40c9-aeae-ccd264470de5':'mark'}
cap=sys.argv[1]
gold=json.load(open('/tmp/metric_gold.json'))
adj=json.load(open(sys.argv[2])) if len(sys.argv)>2 and os.path.exists(sys.argv[2]) else {}
adj_off=adj; adj_on=adj.get('_ontopic',{}) if isinstance(adj,dict) else {}
f1s=[]; absent_ok=0; absent_tot=0; drift_total=0; unknowns=[]
rows=[]
for f in sorted(glob.glob(cap+'/*.json')):
    base=os.path.basename(f)[:-5]; uuid,topic=base.split('__'); cell=uuid+'|'+topic
    g=gold.get(cell); 
    if not g: continue
    d=json.load(open(f)); refs=d.get('references',[]); ans=d.get('answer','') or ''
    cited=[r.get('resourceUuid') for r in refs if r.get('resourceUuid')]
    cited=list(dict.fromkeys(cited))  # dedup, keep order
    onset=set(g['ontopic'])|set(adj_on.get(cell,[]))
    focus=set(g['focus_uuids']); offadj=set(adj_off.get(cell,[]))
    on=[c for c in cited if c in onset]
    off=[c for c in cited if c not in onset and (c in focus or c in offadj)]
    unk=[c for c in cited if c not in onset and c not in focus and c not in offadj]
    for c in unk:
        r=next((x for x in refs if x.get('resourceUuid')==c),{})
        unknowns.append((cell,c,r.get('resourceType'),r.get('index'),ans[:110]))
    if g['present']:
        ncited=len(on)+len(off)+len(unk)
        prec=len(on)/ncited if ncited else (1.0 if not g['ontopic'] else 0.0)
        rec=len(on)/len(g['ontopic']) if g['ontopic'] else 1.0
        f1=2*prec*rec/(prec+rec) if (prec+rec)>0 else 0.0
        f1s.append(f1); drift_total+=len(off)+len(unk)
        rows.append((PN.get(uuid,uuid),topic,'present',len(on),len(off),len(unk),'%.2f'%prec,'%.2f'%rec,'%.2f'%f1))
    else:
        absent_tot+=1; ok=(len(cited)==0); absent_ok+=1 if ok else 0; drift_total+=len(cited)
        rows.append((PN.get(uuid,uuid),topic,'absent',len(on),len(off),len(unk),'-','-','OK' if ok else 'DRIFT'))
print('%-8s %-11s %-7s %3s %3s %3s %5s %5s %5s'%('patient','topic','kind','on','off','unk','prec','rec','f1'))
print('-'*70)
for r in sorted(rows): print('%-8s %-11s %-7s %3d %3d %3d %5s %5s %5s'%r)
print('\nAGGREGATE: present_cells=%d meanF1=%.3f | absent_cells=%d abstention_acc=%.2f | total_offtopic_citations(drift)=%d'%(
   len(f1s), sum(f1s)/len(f1s) if f1s else 0, absent_tot, absent_ok/absent_tot if absent_tot else 0, drift_total))
if unknowns:
    print('\n=== %d UNKNOWN cited records (out-of-focus, NEED ADJUDICATION) ==='%len(unknowns))
    for cell,c,rt,idx,ans in unknowns:
        print('  %-22s %-12s idx=%-4s %s'%(cell.split("|")[1]+":"+cell[:8], rt, idx, c))
        print('        ans: %s'%ans)
