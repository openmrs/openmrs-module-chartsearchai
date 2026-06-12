#!/bin/bash
# Standalone-persona variant of capture_eval.sh (same personas, this install's uuids).
AUTH="${OPENMRS_AUTH:-admin:Admin123}"; BASE="${OPENMRS_REST:-http://localhost:8081/openmrs/ws/rest/v1}"
OUT="$1"; mkdir -p "$OUT"
PATIENTS=("c75bce49-60de-493f-a8a9-458674e7adf3" "2c384236-7c4f-4971-9c57-05d0069d3bbd" "60369905-12b3-4c57-99c4-817aa7d3ae4c" "f25ba560-187e-4fe0-80ce-28bbaa5eed1d")
QUERIES=("Is the patient enrolled in any programs?" "Does the patient have any allergies?" "What medications is the patient taking?" "Does the patient have any eye problems?" "Does the patient have any heart or cardiac problems?" "Has the patient had any fractures or broken bones?" "Does the patient have any kidney problems?" "Does the patient have any mental health or psychiatric conditions?")
topic_of() {
  case "$1" in
    *programs*) echo programs;; *allergies*) echo allergies;; *medications*) echo medications;;
    *eye*) echo eye;; *heart*) echo heart;; *fractures*) echo fractures;;
    *kidney*) echo kidney;; *mental*) echo mental;; *) echo unknown;;
  esac
}
for uuid in "${PATIENTS[@]}"; do
  for q in "${QUERIES[@]}"; do
    t=$(topic_of "$q")
    out="$OUT/${uuid}__${t}.json"
    code=$(curl -s -u "$AUTH" --max-time 300 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
      -d "$(printf '{"patient":"%s","question":"%s"}' "$uuid" "$q")" -o "$out" -w "%{http_code}")
    if [ "$code" != 200 ]; then echo "WARN: $uuid/$t HTTP $code (not scored)" >&2; mv "$out" "$out.err" 2>/dev/null; fi
    echo "$t/$uuid: $code"
  done
done
echo "CAPTURE_DONE $(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') cells -> $OUT"
