// Self-test for the backend gate's own logic. It drives the REAL `classify` and `runGate`, imported
// from the gate, with a scripted sequence of reads and a fake clock, so no browser and no network.
//
// Usage: node .github/scripts/assert-backend-started.test.mjs

const gate = await import('./assert-backend-started.mjs');

let failed = 0;
const check = (name, ok, detail) => {
  if (!ok) failed++;
  console.log(`${ok ? 'ok   ' : 'FAIL '} ${name}${detail ? ` — ${detail}` : ''}`);
};

const MIN = 60_000;
const OPTS = { deadlineMs: 45 * MIN, soakMs: 5 * MIN, intervalMs: MIN / 3, unreachableMs: 5 * MIN };

const r = (status, extra = {}) => ({ status, mitigated: null, body: '', ...extra });
const OK = r(200);
const BOOTING = r(503);
const NO_UPSTREAM = r(502);
const CF_523 = r(523, { body: '<!DOCTYPE html>' });
const CHALLENGE = r(403, { mitigated: 'challenge' });
const THREW = r(0, { error: 'net::ERR_CONNECTION_RESET' });

/** Runs the real loop over `script`, one entry per read; the last entry repeats forever. */
async function drive(script, opts = OPTS) {
  let clock = 0;
  let i = 0;
  const result = await gate.runGate({
    read: async () => script[Math.min(i++, script.length - 1)],
    now: () => clock,
    wait: async (ms) => { clock += ms; },
    log: () => {},
    ...opts,
  });
  return { ...result, reads: i, elapsed: clock };
}
const times = (n, read) => Array.from({ length: n }, () => read);

// ---- classify --------------------------------------------------------------------------------
check('200 with no body is started', gate.classify(OK) === 'started');
check('200 with an HTML body is not started', gate.classify(r(200, { body: '<!doctype html><html>' })) === 'unexpected');
check('503 is starting', gate.classify(BOOTING) === 'starting');
check('502 is starting', gate.classify(NO_UPSTREAM) === 'starting');
check('523 is origin-unreachable', gate.classify(CF_523) === 'origin-unreachable');
check('521 is origin-unreachable', gate.classify(r(521)) === 'origin-unreachable');
check('a cf-mitigated 403 is a challenge', gate.classify(CHALLENGE) === 'challenged');
check('a bare 403 is not a challenge', gate.classify(r(403)) === 'unexpected');
check('a thrown fetch is network', gate.classify(THREW) === 'network');
check('404 is missing', gate.classify(r(404)) === 'missing');

// ---- runGate: passes ----------------------------------------------------------------------------
{
  const res = await drive([...times(30, BOOTING), OK]);
  check('a slow boot that then stays up passes', res.ok === true, res.reason);
  check('it soaks the full window before passing', res.ok && res.soakedMs >= OPTS.soakMs, `soaked ${res.soakedMs}`);
}
{
  const res = await drive([BOOTING, OK, CHALLENGE, THREW, OK]);
  check('a challenge or a thrown read during the soak is tolerated', res.ok === true, res.reason);
}

// ---- runGate: fails ---------------------------------------------------------------------------
{
  // The 2026-11-03 shape: up, then the origin disappears.
  const res = await drive([BOOTING, OK, OK, CF_523]);
  check('started then unreachable fails', res.ok === false && /then answered HTTP 523/.test(res.reason), res.reason);
}
{
  const res = await drive([OK, OK, BOOTING]);
  check('started then 503 again (a restart loop) fails', res.ok === false && /then answered HTTP 503/.test(res.reason), res.reason);
}
{
  const res = await drive([BOOTING]);
  check('never starting fails at the deadline', res.ok === false && /had not started/.test(res.reason), res.reason);
  check('and not before it', res.elapsed >= OPTS.deadlineMs, `elapsed ${res.elapsed}`);
}
{
  const res = await drive([BOOTING, CF_523]);
  check('a sustained 523 before start fails early', res.ok === false && /could not reach the origin/.test(res.reason), res.reason);
  check('well before the start deadline', res.elapsed < OPTS.deadlineMs / 2, `elapsed ${res.elapsed}`);
}
{
  // The recreate window: a short 523 run must not fail the gate.
  const res = await drive([...times(6, CF_523), BOOTING, OK]);
  check('a brief 523 during the recreate is tolerated', res.ok === true, res.reason);
}
{
  // Alternating so no single 523 run reaches the window: the unreachable clock must reset.
  const res = await drive([...Array.from({ length: 40 }, (_, k) => (k % 2 ? BOOTING : CF_523)), OK]);
  check('interleaved 523s do not accumulate into a failure', res.ok === true, res.reason);
}
{
  const res = await drive([r(404)]);
  check('a missing endpoint fails on the first read', res.ok === false && res.reads === 1 && /404/.test(res.reason), res.reason);
}
{
  const res = await drive([OK, CHALLENGE]);
  check('a soak that is never completed is not a pass', res.ok === false && /soak could not be completed/.test(res.reason), res.reason);
}
{
  const res = await drive([r(200, { body: '<!doctype html><html>' })]);
  check('an HTML 200 never counts as started', res.ok === false && /had not started/.test(res.reason), res.reason);
}

console.log(failed ? `\n${failed} check(s) FAILED` : '\nall checks passed');
process.exit(failed ? 1 : 0);
