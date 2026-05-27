#!/usr/bin/env python3
"""List + REST-resolve out-of-focus cited records not yet in gold or adjudications.
Usage: resolve_unknowns.py <capture_dir>  (reads metric_gold.json + offtopic_adj.json alongside this script)"""
import json,glob,os,sys,urllib.request,base64
HERE=os.path.dirname(os.path.abspath(__file__))
AUTH=base64.b64encode(os.environ.get("OPENMRS_AUTH","admin:Admin123").encode()).decode()
BASE=os.environ.get("OPENMRS_REST","http://localhost:8080/openmrs/ws/rest/v1")
EP={'obs':'obs','condition':'condition','diagnosis':'patientdiagnoses','test_order':'order',
    'encounter':'encounter','visit':'visit','program':'programenrollment','appointments_appointment':'appointmentscheduling/appointment'}
def disp(rt,u):
    try:
        r=urllib.request.Request(f"{BASE}/{EP.get(rt,rt)}/{u}?v=custom:(display)",headers={'Authorization':'Basic '+AUTH})
        return json.load(urllib.request.urlopen(r,timeout=10)).get('display','?')
    except Exception as e: return f"(unresolved {rt}: {e})"
gold=json.load(open(os.path.join(HERE,'metric_gold.json')))
_adjp=os.path.join(HERE,'offtopic_adj.json')
adj=json.load(open(_adjp)) if os.path.exists(_adjp) else {}
adj_on=adj.get('_ontopic',{})
PN={'4acc0b80-83c4-40f7-86fd-0e11a68dd405':'betty','07e26b8e-00a9-4b31-b805-3560ad4e9e2e':'richard','be83f269-66bd-4ba1-80ec-cc62d0d0c84e':'karen','61d0a9db-d35f-40c9-aeae-ccd264470de5':'mark'}
n=0
for f in sorted(glob.glob(sys.argv[1]+'/*.json')):
    base=os.path.basename(f)[:-5]
    if '__' not in base: continue
    uuid,topic=base.split('__',1); cell=uuid+'|'+topic
    g=gold.get(cell);
    if not g: continue
    known=set(g['ontopic'])|set(g['focus_uuids'])|set(adj.get(cell,[]))|set(adj_on.get(cell,[]))
    d=json.load(open(f))
    for r in d.get('references',[]):
        cu=r.get('resourceUuid')
        if cu and cu not in known:
            n+=1; print('%-22s %-12s idx=%-4s %s :: %s'%(topic+':'+PN.get(uuid,uuid), r.get('resourceType'), r.get('index'), cu, disp(r.get('resourceType'),cu)))
print('\n%d new unknowns to adjudicate'%n)
