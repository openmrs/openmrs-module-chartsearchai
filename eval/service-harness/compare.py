"""Compare two runs of the service harness (LlmInferenceServiceModelReadQuestionContextTest#harness).

usage: python3 compare.py OLD.jsonl NEW.jsonl [chars]

Prints how many questions changed and, for each, whether the module answered, the chip count, whether the stub rewrote
it, and the answer's start, old then new. A question with an "error" field threw; it is counted, never skipped.
"""
import json
import sys


def load(path):
    with open(path) as f:
        return {row['q']: row for row in map(json.loads, f)}


def main():
    old, new = load(sys.argv[1]), load(sys.argv[2])
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 200
    changed = [q for q in old if q in new and (old[q].get('answer'), old[q].get('chips'), old[q].get('module'))
               != (new[q].get('answer'), new[q].get('chips'), new[q].get('module'))]
    missing = [q for q in old if q not in new]
    errors = sum(1 for row in new.values() if row.get('error'))
    print('%d questions; %d changed; %d missing from the new run; %d errors' % (len(old), len(changed), len(missing), errors))
    for q in changed:
        print('==', q)
        for label, row in (('OLD', old[q]), ('NEW', new[q])):
            text = (row.get('answer') or row.get('error') or '').replace('\n', ' / ')
            print('  %s module=%s chips=%d rewritten=%s %s' % (label, row.get('module'), len(row.get('chips') or []),
                                                             row.get('rewritten'), text[:width]))


if __name__ == '__main__':
    main()
