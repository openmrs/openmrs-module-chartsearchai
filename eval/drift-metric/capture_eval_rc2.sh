#!/bin/bash
# rc.2-standalone capture: the four rc.2 gold patients (see build_gold_rc2.py / gold_audit.rc2.md).
AUTH="${OPENMRS_AUTH:-admin:Admin123}"; BASE="${OPENMRS_REST:-http://localhost:8081/openmrs/ws/rest/v1}"
OUT="$1"; mkdir -p "$OUT"
PATIENTS=("bc4ba445-a35c-4996-b804-4d5b68387571" "1128c659-2d0a-4314-af23-91bac1b01109" "59a5f0bb-b863-4213-9177-b883fe9f5f79" "16ca09dd-a8d4-405a-bda6-76d18ed65b25" "dkb00000-0000-0000-0000-000000000001")
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
