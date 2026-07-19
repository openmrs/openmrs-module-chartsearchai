#!/bin/bash
# rc.2-standalone capture: the four rc.2 gold patients (see build_gold_rc2.py / gold_audit.rc2.md).
AUTH="${OPENMRS_AUTH:-admin:Admin123}"; BASE="${OPENMRS_REST:-http://localhost:8081/openmrs/ws/rest/v1}"
OUT="$1"; mkdir -p "$OUT"
# The 22 widened-gold patients (2026-07-19), matching PINNED in build_gold_rc2.py so a default
# run reproduces the committed metric_gold.rc2.json. The original five stay first.
PATIENTS=("bc4ba445-a35c-4996-b804-4d5b68387571" "1128c659-2d0a-4314-af23-91bac1b01109" "59a5f0bb-b863-4213-9177-b883fe9f5f79" "16ca09dd-a8d4-405a-bda6-76d18ed65b25" "dkb00000-0000-0000-0000-000000000001" "813b9f0d-3a8e-4f67-a0dd-d9b3eeef65c5" "489db738-ad5f-4335-a9f1-270ec0c76ea2" "5b24c81f-2b66-41ed-8f2d-158433d531cc" "b360ca49-d432-404f-9b6c-aa6a66125693" "bd3927f4-8c75-470b-bdbb-6c92857b2205" "c34d0124-76c9-4197-9f84-35e44e1317a8" "007e38b8-1344-4ea9-a790-a3f078471db3" "089f766b-943c-427b-a039-672f90b0a49e" "520016d7-67ae-40fa-aa8f-e8a5ec2b8fd6" "8d9fc13a-5d4c-4c5c-b265-23ac067835a4" "53927035-f177-4144-9d3f-b80ced7614bc" "072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9" "0563178c-e107-43a0-be05-2a179ab02dbe" "3d6b5ada-c402-4f3f-9c70-6a17f4d2a339" "ec6af3d5-3082-45f4-8f14-cf42dad41ed2" "47028119-e1e0-467c-b807-a23d1a81fb2b" "e9712a18-c181-46c5-8a17-46b02e39b23b")
# Override for supplemental runs: CAPTURE_PATIENTS="uuid1 uuid2" capture_eval_rc2.sh <outdir>
if [ -n "$CAPTURE_PATIENTS" ]; then read -r -a PATIENTS <<< "$CAPTURE_PATIENTS"; fi
QUERIES=("Is the patient enrolled in any programs?" "Does the patient have any allergies?" "What medications is the patient taking?" "Does the patient have any eye problems?" "Does the patient have any heart or cardiac problems?" "Has the patient had any fractures or broken bones?" "Does the patient have any kidney problems?" "Does the patient have any mental health or psychiatric conditions?" "Does the patient have any drug allergies?")
topic_of() {
  case "$1" in
    *drug\ allergies*) echo drug-allergies;;
    *programs*) echo programs;; *allergies*) echo allergies;; *medications*) echo medications;;
    *eye*) echo eye;; *heart*) echo heart;; *fractures*) echo fractures;;
    *kidney*) echo kidney;; *mental*) echo mental;; *) echo unknown;;
  esac
}
for uuid in "${PATIENTS[@]}"; do
  for q in "${QUERIES[@]}"; do
    t=$(topic_of "$q")
    out="$OUT/${uuid}__${t}.json"
    code=$(curl -s -u "$AUTH" --max-time 600 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
      -d "$(printf '{"patient":"%s","question":"%s"}' "$uuid" "$q")" -o "$out" -w "%{http_code}")
    if [ "$code" != 200 ]; then echo "WARN: $uuid/$t HTTP $code (not scored)" >&2; mv "$out" "$out.err" 2>/dev/null; fi
    echo "$t/$uuid: $code"
  done
done
echo "CAPTURE_DONE $(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') cells -> $OUT"
