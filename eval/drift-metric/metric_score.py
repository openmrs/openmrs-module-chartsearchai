#!/usr/bin/env python3
"""Honest-metric scorer. UUID set comparison vs human gold (no model-derived drift terms).

Usage:
  metric_score.py <capture_dir> [offtopic_adj.json] [metric_gold.json]
  metric_score.py --selftest

  capture_dir: per-cell response JSON {answer, references:[{resourceUuid,index,resourceType}]}
  offtopic_adj.json (default: alongside this script): out-of-focus cited records adjudicated.
      {"<patientUuid|topic>": ["uuid",...]}  -> OFF-topic
      {"_ontopic": {"<cell>": ["uuid",...]}} -> ON-topic
  metric_gold.json (default: alongside this script): per cell {present, ontopic{uuid:text}, focus_uuids{uuid:text}}.

Each cited record is classified ON-topic (gold.ontopic + adjudicated _ontopic) / OFF-topic
(in the top-30 focus but not on-topic, OR adjudicated off) / UNKNOWN (out-of-focus, not yet
adjudicated). Present cells -> precision/recall/F1; absent cells -> abstention correctness.
Recall denominator = the full on-topic universe (focus-30 on-topic + adjudicated out-of-focus
on-topic), so recall is bounded by 1.0 and matches the README's expanding-gold definition.
A record listed in BOTH off_adj and on_adj counts ON (on_adj wins)."""
import json, sys, glob, os

HERE = os.path.dirname(os.path.abspath(__file__))
PN = {'4acc0b80-83c4-40f7-86fd-0e11a68dd405': 'betty', '07e26b8e-00a9-4b31-b805-3560ad4e9e2e': 'richard',
      'be83f269-66bd-4ba1-80ec-cc62d0d0c84e': 'karen', '61d0a9db-d35f-40c9-aeae-ccd264470de5': 'mark'}


def score_cell(cited, present, ontopic, focus, off_adj, on_adj):
    """Pure scoring of one cell. cited: ordered unique cited uuids. ontopic/focus/off_adj/on_adj: sets.
    on_adj wins over off_adj on conflict. Returns dict with on/off/unk lists + present, and for present
    cells prec/rec/f1 (None when 'scoreable' is False, i.e. no known on-topic record exists for the cell),
    for absent cells abstain_ok. Recall denominator is the full on-topic universe (ontopic | on_adj)."""
    onset = ontopic | on_adj
    on = [c for c in cited if c in onset]
    off = [c for c in cited if c not in onset and (c in focus or c in off_adj)]
    unk = [c for c in cited if c not in onset and c not in focus and c not in off_adj]
    r = {'on': on, 'off': off, 'unk': unk, 'present': present}
    if present:
        r['scoreable'] = bool(onset)
        if not onset:
            # present cell whose on-topic records are all out-of-focus and not yet adjudicated:
            # unscoreable for precision/recall. Do NOT award a free F1=1.0 for abstaining here.
            r['prec'] = r['rec'] = r['f1'] = None
        else:
            ncited = len(on) + len(off) + len(unk)
            r['prec'] = len(on) / ncited if ncited else 0.0
            r['rec'] = len(on) / len(onset)
            r['f1'] = 2 * r['prec'] * r['rec'] / (r['prec'] + r['rec']) if (r['prec'] + r['rec']) > 0 else 0.0
    else:
        r['abstain_ok'] = (len(cited) == 0)
    return r


def selftest():
    O = lambda *xs: set(xs)
    # present: 3 on-topic, cites 2 on + 1 in-focus-off -> prec 2/3, rec 2/3
    r = score_cell(['a', 'b', 'x'], True, O('a', 'b', 'c'), O('a', 'b', 'c', 'x', 'y'), O(), O())
    assert (len(r['on']), len(r['off']), len(r['unk'])) == (2, 1, 0), r
    assert abs(r['prec'] - 2/3) < 1e-9 and abs(r['rec'] - 2/3) < 1e-9, r
    # recall-fix: 2 focus-on-topic + 1 adjudicated out-of-focus on-topic, cites all 3 -> rec == 1.0 (not 1.5)
    r = score_cell(['a', 'b', 'z'], True, O('a', 'b'), O('a', 'b'), O(), O('z'))
    assert len(r['on']) == 3 and abs(r['rec'] - 1.0) < 1e-9, ('recall must be bounded by 1.0', r)
    # OFF via off_adj (out-of-focus adjudicated off-topic), NOT via focus membership
    r = score_cell(['z'], True, O('a'), O('a'), O('z'), O())
    assert len(r['off']) == 1 and len(r['unk']) == 0, ('off_adj must classify OFF', r)
    # conflict: a uuid in both off_adj and on_adj counts ON (on_adj wins)
    r = score_cell(['z'], True, O('a'), O('a'), O('z'), O('z'))
    assert len(r['on']) == 1 and len(r['off']) == 0, ('on_adj must win conflict', r)
    # unknown: out-of-focus, unadjudicated
    r = score_cell(['q'], True, O('a'), O('a'), O(), O())
    assert len(r['unk']) == 1 and r['rec'] == 0.0, r
    # present cell with empty on-topic universe -> unscoreable, no free F1=1.0
    r = score_cell([], True, O(), O(), O(), O())
    assert r['scoreable'] is False and r['f1'] is None, ('empty-onset present cell must be unscoreable', r)
    # absent: abstain vs drift
    assert score_cell([], False, O(), O(), O(), O())['abstain_ok'] is True
    assert score_cell(['p'], False, O(), O('p'), O(), O())['abstain_ok'] is False
    print('selftest OK')


def main():
    cap = sys.argv[1]
    adj_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, 'offtopic_adj.json')
    gold_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(HERE, 'metric_gold.json')
    gold = json.load(open(gold_path))
    adj = json.load(open(adj_path)) if os.path.exists(adj_path) else {}
    adj_on = adj.get('_ontopic', {})
    f1s = []; absent_ok = 0; absent_tot = 0; drift_total = 0; unknowns = []; rows = []; scored = set(); skipped = 0
    for f in sorted(glob.glob(cap + '/*.json')):
        base = os.path.basename(f)[:-5]
        if '__' not in base:
            continue
        uuid, topic = base.split('__', 1); cell = uuid + '|' + topic
        g = gold.get(cell)
        if not g:
            continue
        try:
            d = json.load(open(f))
        except (ValueError, OSError) as e:
            print('WARN: unreadable capture %s (%s) — skipped' % (base, e)); skipped += 1; continue
        if 'references' not in d and 'answer' not in d:
            # e.g. an HTTP error body ({"error":{...}}) written by a failed curl — not a real answer.
            print('WARN: capture %s has neither references nor answer (error body?) — skipped' % base); skipped += 1; continue
        refs = d.get('references', []); ans = d.get('answer', '') or ''
        cited = list(dict.fromkeys(r.get('resourceUuid') for r in refs if r.get('resourceUuid')))
        s = score_cell(cited, g['present'], set(g['ontopic']), set(g['focus_uuids']),
                       set(adj.get(cell, [])), set(adj_on.get(cell, [])))
        scored.add(cell)
        for c in s['unk']:
            r = next((x for x in refs if x.get('resourceUuid') == c), {})
            unknowns.append((cell, c, r.get('resourceType'), r.get('index'), ans[:110]))
        nm = PN.get(uuid, uuid)
        if s['present']:
            if not s['scoreable']:
                print('WARN: present cell %s|%s has no known on-topic record (all out-of-focus, unadjudicated) — excluded from meanF1' % (nm, topic))
                rows.append((nm, topic, 'present', len(s['on']), len(s['off']), len(s['unk']), 'n/a', 'n/a', 'n/a'))
            else:
                f1s.append(s['f1']); drift_total += len(s['off']) + len(s['unk'])
                rows.append((nm, topic, 'present', len(s['on']), len(s['off']), len(s['unk']),
                             '%.2f' % s['prec'], '%.2f' % s['rec'], '%.2f' % s['f1']))
        else:
            absent_tot += 1; absent_ok += 1 if s['abstain_ok'] else 0; drift_total += len(s['on']) + len(s['off']) + len(s['unk'])
            rows.append((nm, topic, 'absent', len(s['on']), len(s['off']), len(s['unk']),
                         '-', '-', 'OK' if s['abstain_ok'] else 'DRIFT'))
    print('%-8s %-11s %-7s %3s %3s %3s %5s %5s %5s' % ('patient', 'topic', 'kind', 'on', 'off', 'unk', 'prec', 'rec', 'f1'))
    print('-' * 70)
    for r in sorted(rows):
        print('%-8s %-11s %-7s %3d %3d %3d %5s %5s %5s' % r)
    print('\nAGGREGATE: present_cells_scored=%d meanF1=%.3f | absent_cells=%d abstention_acc=%.2f | total_offtopic_citations(drift)=%d' % (
        len(f1s), sum(f1s) / len(f1s) if f1s else 0, absent_tot, absent_ok / absent_tot if absent_tot else 0, drift_total))
    missing = set(gold) - scored
    if missing or skipped:
        print('WARN: scored %d/%d gold cells (%d skipped-unreadable, %d missing from capture: %s)' % (
            len(scored), len(gold), skipped, len(missing), ', '.join(sorted(missing)[:5]) + ('…' if len(missing) > 5 else '')))
    if unknowns:
        print('\n=== %d UNKNOWN cited records (out-of-focus, NEED ADJUDICATION) ===' % len(unknowns))
        for cell, c, rt, idx, ans in unknowns:
            print('  %-22s %-12s idx=%-4s %s' % (cell.split('|')[1] + ':' + cell[:8], rt, idx, c))
            print('        ans: %s' % ans)


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--selftest':
        selftest()
    else:
        main()
