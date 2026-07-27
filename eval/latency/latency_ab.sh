#!/bin/bash
# Controlled A/B/A wall-clock comparison on ONE fixed cell set, machine otherwise idle.
# A = the change set (this working tree's build), B = clean main (built in /tmp/csa-main).
# A is measured twice so machine drift over the run is visible rather than attributed to the code.
SA=/Users/danielkayiwa/Downloads/referenceapplication-standalone-3.7.1
REPO=${REPO:?set REPO to the module checkout}
A_OMOD=$REPO/omod/target/chartsearchai-1.0.0-SNAPSHOT.omod
B_OMOD=/tmp/csa-main/omod/target/chartsearchai-1.0.0-SNAPSHOT.omod
AUTH=admin:Admin123; BASE=http://localhost:8081/openmrs/ws/rest/v1
PATIENTS=(1c47b620-080f-4484-8372-74e904165aec dc8560c9-6d2b-45bf-861c-8fcf562ec9b1 bbdd58b1-97c1-4488-acae-582abb1b782d)
QUERIES=("Is the patient enrolled in any programs?" "Does the patient have any allergies?" "What medications is the patient taking?" "Does the patient have any eye problems?" "Does the patient have any heart or cardiac problems?" "Has the patient had any fractures or broken bones?" "Does the patient have any kidney problems?" "Does the patient have any mental health or psychiatric conditions?" "Does the patient have any drug allergies?")

deploy() {  # $1 = omod path, $2 = label
  pkill -9 -f openmrs-standalone.jar 2>/dev/null; pkill -9 -f llama-server 2>/dev/null
  pkill -9 -f "mariadbd.*3316" 2>/dev/null
  while lsof -nP -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1; do sleep 1; done
  while lsof -nP -iTCP:3316 -sTCP:LISTEN >/dev/null 2>&1; do sleep 1; done
  cp "$1" "$SA/appdata/modules/chartsearchai-1.0.0-SNAPSHOT.omod"
  rm -rf "$SA/appdata/.openmrs-lib-cache/chartsearchai"
  (cd "$SA" && nohup java -jar openmrs-standalone.jar > "/tmp/standalone-lat-$2.log" 2>&1 &)
  until curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8081/openmrs/ 2>/dev/null | grep -qE "200|302"; do sleep 5; done
}

runset() {  # $1 = label
  # one warm-up call so the model load / first-touch cost is not attributed to a measured cell
  curl -s -u "$AUTH" --max-time 600 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
       -d '{"patient":"1c47b620-080f-4484-8372-74e904165aec","question":"warmup"}' -o /dev/null
  for uuid in "${PATIENTS[@]}"; do
    for q in "${QUERIES[@]}"; do
      t=$( { /usr/bin/time -p curl -s -u "$AUTH" --max-time 600 -H "Content-Type: application/json" \
             -X POST "$BASE/chartsearchai/search" -d "$(printf '{"patient":"%s","question":"%s"}' "$uuid" "$q")" \
             -o /dev/null ; } 2>&1 | awk '/^real/{print $2}')
      echo "$1 $uuid $t"
    done
  done
}

echo "### PASS 1: A (change set)"
deploy "$A_OMOD" a1; runset A1
echo "### PASS 2: B (clean main)"
deploy "$B_OMOD" b;  runset B
echo "### PASS 3: A again (drift control)"
deploy "$A_OMOD" a2; runset A2
echo "### LATENCY_AB_DONE"
