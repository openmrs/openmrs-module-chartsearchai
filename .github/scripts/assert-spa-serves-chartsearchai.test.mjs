// Self-test for the post-deploy gate's own logic.
//
// Why this exists. Every assertion the gate makes used to be executed for the first time against
// production: a wrong predicate would either wave a stale deploy through or fail every healthy
// one, and either way the first person to find out would be whoever read the red run. Two
// predicates in this gate's history were wrong in exactly those two directions — an equality test
// on Last-Modified that could never hold, and a hard comparison against a branch tip that nothing
// keeps the image in step with.
//
// It drives the REAL functions, imported from the gate — which is inert on import because it
// gates its run block on entry-point identity, not on an environment variable (one commit used
// GATE_SELFTEST for that, and any value of it silenced the gate itself). `page` is stubbed to run
// the evaluated function in Node and `fetch` is stubbed per case, so `probe` and `readHead`
// execute their own code — the cache-busting and the challenge poll included — not a copy.
//
// The page-side functions cannot close over module scope in a real browser, so anything they use
// is passed in. Running them in Node would hide a violation of that, which is why the gate passes
// the buster seed and the poll interval explicitly.
//
// Usage: node .github/scripts/assert-spa-serves-chartsearchai.test.mjs

process.env.GATE_CHALLENGE_MS = '50';
process.env.GATE_POLL_MS = '5';
process.env.ESM_EXPECTED_SHA = 'a'.repeat(40);

const gate = await import('./assert-spa-serves-chartsearchai.mjs');

let failed = 0;
const check = (name, ok, detail) => {
  if (!ok) failed++;
  console.log(`${ok ? 'ok   ' : 'FAIL '} ${name}${detail ? ` — ${detail}` : ''}`);
};
const has = (problems, fragment) => problems.some((p) => p.includes(fragment));

const SHA = 'a'.repeat(40);
const STAMP = 'Wed, 16 Sep 2026 08:57:04 GMT';
const ENTRY_AT = 'Wed, 16 Sep 2026 09:00:20 GMT';
const res = (body, lastModified = STAMP, status = 200, extra = {}) => ({
  status,
  encoding: null,
  lastModified,
  body,
  ...extra,
});
const importmapFor = (spec) =>
  res(JSON.stringify({ imports: { '@openmrs/esm-chartsearchai-app': spec } }));
const routes = res('{"chartsearchai":true}');
const entryHead = (over = {}) => ({ url: '/x/app.js', status: 200, lastModified: ENTRY_AT, ...over });

// ---- problemsWith: the verdicts -------------------------------------------------------------
{
  const { problems, warnings } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA) },
    entryHead(),
  );
  check('healthy deployment passes', problems.length === 0 && warnings.length === 0, problems.join('; '));
}
{
  // The 2026-09-15 defect, with its real timestamps.
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA, 'Tue, 15 Sep 2026 21:01:32 GMT') },
    entryHead({ lastModified: 'Tue, 15 Sep 2026 10:05:20 GMT' }),
  );
  check('a pre-dating entry bundle fails', has(problems, 'pre-dates this build'), problems.join('; '));
}
{
  // Must NOT fail: nothing rebuilds the image when the ESM repo moves.
  const { problems, warnings } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res('b'.repeat(40)) },
    entryHead(),
  );
  check('an ESM tip ahead of the image warns, never fails', problems.length === 0 && warnings.length === 1, problems.join('; '));
}
{
  const { problems } = gate.problemsWith({ importmap: importmapFor(''), routes, esmSha: res(SHA) }, null);
  check('an empty specifier fails', has(problems, 'no usable specifier'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('https://cdn.example.org/esm/x.js'), routes, esmSha: res(SHA) },
    null,
  );
  check('a foreign-origin specifier fails', has(problems, 'another origin'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res('<!doctype html><html>') },
    entryHead(),
  );
  check('a stamp whose body is not a sha fails', has(problems, 'is not a commit sha'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA, null) },
    entryHead(),
  );
  check('a missing Last-Modified fails loudly, never silently skips', has(problems, 'cannot compare build provenance'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA) },
    entryHead({ status: 403, lastModified: null }),
  );
  check('a 403 entry reads as unreadable, not as missing', has(problems, 'could not be read at all'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA) },
    entryHead({ status: 404, lastModified: null }),
  );
  check('a 404 entry reads as not served', has(problems, 'is not served'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res('', null, 404) },
    entryHead(),
  );
  check('an image predating the stamp fails', has(problems, 'returned HTTP 404'), problems.join('; '));
}

// ---- entryUrlFrom: the shapes one specifier can take --------------------------------------
check('relative specifier resolves under the SPA base', gate.entryUrlFrom(importmapFor('./a/b.js').body) === '/openmrs/spa/a/b.js');
check('root-relative specifier is kept', gate.entryUrlFrom(importmapFor('/openmrs/spa/a/b.js').body) === '/openmrs/spa/a/b.js');
check('a foreign specifier is reported, not resolved', gate.entryUrlFrom(importmapFor('https://cdn.example.org/a.js').body)?.foreign !== undefined);
check('an absent specifier is null', gate.entryUrlFrom('{"imports":{}}') === null);
check('unparseable json is null', gate.entryUrlFrom('not json') === null);

// ---- probe / readHead: the real fetch paths ------------------------------------------------
const page = { route: async () => {}, goto: async () => {}, evaluate: async (fn, arg) => fn(arg) };
{
  const seen = [];
  globalThis.fetch = async (u) => {
    seen.push(u);
    return { status: 200, text: async () => 'body', headers: { get: () => null } };
  };
  await gate.probe(page, { importmap: '/openmrs/spa/importmap.json' });
  await gate.readHead(page, '/openmrs/spa/x/app.js');
  check('probe cache-busts its reads', seen.some((u) => u.startsWith('/openmrs/spa/importmap.json?cb=')), seen.join(' '));
  check('readHead cache-busts its read', seen.some((u) => u.startsWith('/openmrs/spa/x/app.js?cb=')), seen.join(' '));
  check('every read is busted, none bare', !seen.some((u) => !u.includes('cb=')), seen.join(' '));
}
{
  // A challenge that clears: 403 first, then 200 — the poll must return the 200.
  let n = 0;
  globalThis.fetch = async () => {
    n++;
    return n === 1
      ? { status: 403, text: async () => '', headers: { get: () => null } }
      : { status: 200, text: async () => SHA, headers: { get: (h) => (h === 'last-modified' ? STAMP : null) } };
  };
  const out = await gate.probe(page, { esmSha: '/openmrs/spa/chartsearchai-esm.sha' });
  check('the challenge poll returns the eventual 200', out.esmSha.status === 200 && out.esmSha.body === SHA, JSON.stringify(out.esmSha.status));
}
{
  // A challenge that never clears must give up and report the 403, not hang.
  globalThis.fetch = async () => ({ status: 403, text: async () => '', headers: { get: () => null } });
  const head = await gate.readHead(page, '/openmrs/spa/x/app.js');
  check('an unrelenting challenge gives up and reports 403', head.status === 403, JSON.stringify(head));
}
{
  globalThis.fetch = async () => {
    throw new Error('net::ERR_ABORTED\nsecond line');
  };
  const head = await gate.readHead(page, '/openmrs/spa/x/app.js');
  check('a throwing fetch reports status 0', head.status === 0 && typeof head.error === 'string', JSON.stringify(head));
}


// ---- the verdict itself, and the predicates the ORIGINAL 2026-08-25 defect was about ---------
check('isHealthy is false for any problem', gate.isHealthy([]) === true && gate.isHealthy(['x']) === false);
{
  const { problems } = gate.problemsWith(
    { importmap: res('{"imports":{"@openmrs/esm-other-app":"./o.js"}}'), routes, esmSha: res(SHA) },
    entryHead(),
  );
  check('an importmap not naming the ESM fails', has(problems, 'none of them is'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes: res('{"other":true}'), esmSha: res(SHA) },
    entryHead(),
  );
  check('routes without a chartsearchai route fails', has(problems, 'names no chartsearchai route'), problems.join('; '));
}
{
  // A usable specifier whose entry was never read must not be green.
  const { problems } = gate.problemsWith({ importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA) }, null);
  check('a specifier whose entry was never read fails', has(problems, 'never read'), problems.join('; '));
}

// ---- the provenance boundary ------------------------------------------------------------------
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA, ENTRY_AT) },
    entryHead({ lastModified: ENTRY_AT }),
  );
  check('identical timestamps pass (the stamp may equal the entry)', problems.length === 0, problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA, 'Wed, 16 Sep 2026 09:00:21 GMT') },
    entryHead({ lastModified: 'Wed, 16 Sep 2026 09:00:20 GMT' }),
  );
  check('one second older than the stamp fails', has(problems, 'pre-dates'), problems.join('; '));
}

// ---- cache visibility and read uniqueness ----------------------------------------------------
{
  const seen = [];
  globalThis.fetch = async (u) => {
    seen.push(u);
    return {
      status: 200,
      text: async () => SHA,
      headers: { get: (h) => ({ 'last-modified': STAMP, 'cf-cache-status': 'HIT', age: '42' })[h] ?? null },
    };
  };
  const head = await gate.readHead(page, '/openmrs/spa/x/app.js');
  check('readHead surfaces cf-cache-status and age', head.cache === 'HIT' && head.age === '42', JSON.stringify(head));
  const probed = await gate.probe(page, { esmSha: '/openmrs/spa/chartsearchai-esm.sha' });
  check('probe surfaces cf-cache-status and age', probed.esmSha.cache === 'HIT' && probed.esmSha.age === '42');
  // Uniqueness, not mere presence: a constant buster is one URL an edge caches forever.
  await gate.readHead(page, '/openmrs/spa/x/app.js');
  await gate.probe(page, { a: '/openmrs/spa/x/app.js' });
  check('every read gets a DISTINCT url, including the same path twice', new Set(seen).size === seen.length, seen.join(' '));
}


// ---- fixtures that match the real artifacts, not tidied versions of them --------------------
{
  // `git rev-parse HEAD >` writes a trailing newline: the artifact is 41 bytes, not 40. Every
  // other case here passes a bare 40-char body, so dropping the .trim() would fail every healthy
  // deploy with this suite green.
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(`${SHA}\n`) },
    entryHead(),
  );
  check('the real 41-byte stamp (trailing newline) passes', problems.length === 0, problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(`${SHA}a`) },
    entryHead(),
  );
  check('41 hex characters is not a sha', has(problems, 'is not a commit sha'), problems.join('; '));
}
{
  // The entry side of the provenance comparison, which no case covered: only the stamp's
  // Last-Modified was ever nulled, so skipping on a headerless ENTRY stayed green.
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA) },
    entryHead({ lastModified: null }),
  );
  check('an entry served without Last-Modified fails', has(problems, 'cannot compare build provenance'), problems.join('; '));
}
{
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res(SHA) },
    entryHead({ status: 0, lastModified: null, error: 'net::ERR_FAILED' }),
  );
  check('a status-0 entry reads as unreadable', has(problems, 'could not be read at all'), problems.join('; '));
}
{
  // An SPA-fallback stamp must not also fabricate a provenance verdict against index.html's mtime.
  const { problems } = gate.problemsWith(
    { importmap: importmapFor('./x/app.js'), routes, esmSha: res('<!doctype html>', 'Tue, 15 Sep 2026 21:01:32 GMT') },
    entryHead({ lastModified: 'Tue, 15 Sep 2026 10:05:20 GMT' }),
  );
  check(
    'a non-sha stamp reports only that, not a fabricated pre-dating',
    has(problems, 'is not a commit sha') && !has(problems, 'pre-dates'),
    problems.join(' | '),
  );
}
{
  // Through probe specifically, reading ONE path twice — the earlier distinctness case used two
  // different paths there, so a constant buster survived in probe while dying in readHead.
  const seen = [];
  globalThis.fetch = async (u) => {
    seen.push(u);
    return { status: 200, text: async () => SHA, headers: { get: () => null } };
  };
  await gate.probe(page, { a: '/openmrs/spa/same.js' });
  await gate.probe(page, { a: '/openmrs/spa/same.js' });
  check('probe never fetches one path at the same URL twice', new Set(seen).size === seen.length, seen.join(' '));
}


{
  // Per POLL, not per call: readHead's buster sat above its loop while probe's sat inside, so a
  // challenged read re-fetched one identical URL to the deadline — an edge-cached 403 would then
  // be returned as unreadable, i.e. red on a healthy deployment.
  const polled = [];
  globalThis.fetch = async (u) => {
    polled.push(u);
    return { status: 403, text: async () => '', headers: { get: () => null } };
  };
  await gate.readHead(page, '/openmrs/spa/x/app.js');
  check(
    'a challenged readHead re-polls at a DIFFERENT url each time',
    polled.length > 2 && new Set(polled).size === polled.length,
    `${polled.length} polls, ${new Set(polled).size} distinct`,
  );
  const probed = [];
  globalThis.fetch = async (u) => {
    probed.push(u);
    return { status: 403, text: async () => '', headers: { get: () => null } };
  };
  await gate.probe(page, { a: '/openmrs/spa/x/app.js' });
  check(
    'a challenged probe re-polls at a DIFFERENT url each time',
    probed.length > 2 && new Set(probed).size === probed.length,
    `${probed.length} polls, ${new Set(probed).size} distinct`,
  );
}


// ---- the gate's own traffic: what invites a challenge, and what survives one -----------------
//
// Measured against the live deployment on 2026-09-20, the day the gate reported nothing but
// refusals over a deployment that was healthy. Cloudflare injects its JS-detection probe
// (`/cdn-cgi/challenge-platform/.../jsd/main.js`) into HTML DOCUMENTS. Navigating to
// `/openmrs/spa/login` pulled it in, the browser posted its fingerprint 1.59s later, and from
// 1.61s every request out of that browsing context — the static files this gate reads included —
// answered `403` with `cf-mitigated: challenge`. The fixed reads are issued together and beat
// that verdict; the entry bundle cannot be, because its URL is only known once the importmap has
// been parsed. So that run's first attempt reported only the entry as unreadable and every later
// one reported the rest, which is exactly what it printed. Navigating to a non-HTML path instead
// made ZERO `/cdn-cgi/` requests — 60 sequential reads in one session and 40 parallel in another
// all returned 200 — and the gate goes further and fulfils its own document, because the 404 page
// nginx answers a missing path with is itself HTML (`content-type: text/html`, measured the same
// day).
{
  const routed = [];
  const handled = [];
  let navigated = null;
  const navPage = {
    route: async (u, handler) => {
      routed.push(u);
      await handler({
        fulfill: async (r) => handled.push(['fulfill', r]),
        continue: async () => handled.push(['continue']),
        abort: async () => handled.push(['abort']),
      });
    },
    goto: async (u) => { navigated = u; },
    evaluate: async (fn, arg) => fn(arg),
  };
  globalThis.fetch = async () => ({ status: 200, text: async () => '{}', headers: { get: () => null } });
  await gate.probe(navPage, { importmap: '/openmrs/spa/importmap.json' });
  check(
    'probe navigates to a document it intercepts, not to one the deployment serves',
    navigated !== null && routed.includes(navigated),
    `navigated ${navigated}, routed ${routed.join(',')}`,
  );
  check(
    'that navigation is FULFILLED, never continued — letting it through is the defect',
    handled.length === 1 && handled[0][0] === 'fulfill' && /^text\/html/.test(handled[0][1].contentType),
    JSON.stringify(handled),
  );
  check(
    'and the document it fulfils is same-origin with the deployment, or the reads are cross-origin',
    navigated !== null && new URL(navigated).origin === 'https://chartsearchai.openmrs.org',
    String(navigated),
  );
}
{
  // The poll re-fetched every `pollMs` for the whole deadline, so a challenged read spent the
  // window re-asking one question, every read did that per attempt, and every attempt repeated
  // it. The schedule bounds the COUNT, which the code decides; the deadline goes on bounding the
  // wall clock, which it does not.
  const waits = gate.pollSchedule(60_000, 2_000);
  check('the poll backs off instead of re-fetching at a fixed interval', waits.length > 1 && waits[1] > waits[0], waits.join(','));
  check('the poll fits inside the deadline it is bounded by', waits.reduce((a, b) => a + b, 0) <= 60_000, waits.join(','));
  check('a deadline shorter than one interval polls nothing', gate.pollSchedule(1, 2_000).length === 0);
  let n = 0;
  globalThis.fetch = async () => {
    n++;
    return { status: 403, text: async () => '', headers: { get: () => null } };
  };
  await gate.readHead(page, '/openmrs/spa/x/app.js');
  const bound = gate.pollSchedule(Number(process.env.GATE_CHALLENGE_MS), Number(process.env.GATE_POLL_MS)).length + 1;
  check('an unrelenting challenge is read a bounded number of times', n === bound, `${n} reads against a bound of ${bound}`);
  // And an UNchallenged probe costs one read per path. The traffic a healthy run makes is the
  // half of this that no deadline bounds at all.
  n = 0;
  globalThis.fetch = async () => {
    n++;
    return { status: 200, text: async () => '{}', headers: { get: () => null } };
  };
  await gate.probe(page, { importmap: '/a.json', routes: '/b.json', esmSha: '/c.sha' });
  check('a probe that is not challenged reads each path exactly once', n === 3, `${n} reads for 3 paths`);
}

{
  // A refused run must name its refuser. The 2026-09-20 failure printed round after round of
  // bare `returned HTTP 403`, then an epilogue about Docker Hub, over a deployment serving the
  // right files — because nothing carried the one header saying the read never reached it.
  const { problems } = gate.problemsWith(
    { importmap: res('', null, 403, { mitigated: 'challenge' }), routes, esmSha: res(SHA) },
    entryHead(),
  );
  check(
    'a Cloudflare refusal says so instead of reading as a deployment fault',
    has(problems, 'returned HTTP 403 (cf-mitigated: challenge)'),
    problems.join('; '),
  );
  globalThis.fetch = async () => ({
    status: 403,
    text: async () => '',
    headers: { get: (h) => (h === 'cf-mitigated' ? 'challenge' : null) },
  });
  const head = await gate.readHead(page, '/openmrs/spa/x/app.js');
  check('readHead carries cf-mitigated out of the response', head.mitigated === 'challenge', JSON.stringify(head));
  const probed = await gate.probe(page, { a: '/openmrs/spa/x/app.js' });
  check('probe carries cf-mitigated out of the response', probed.a.mitigated === 'challenge', JSON.stringify(probed.a));
}

// ---- one browsing context per attempt --------------------------------------------------------
//
// Cloudflare's verdict attaches to the CONTEXT, not to the address: a context opened after the
// flagged one read the same deployment fine, from the same machine, seconds later. The gate held
// ONE page for every attempt, so once flagged it re-ran the same refusal to the end of the loop
// and reported the last. A context per attempt is what makes the retry loop a retry and not a
// repeat.
{
  const opened = [];
  let closed = 0;
  const newSession = async () => {
    const session = {
      page: { route: async () => {}, goto: async () => {}, evaluate: async (fn, arg) => fn(arg) },
      close: async () => { closed++; },
    };
    opened.push(session);
    return session;
  };
  globalThis.fetch = async () => ({ status: 403, text: async () => '', headers: { get: () => null } });
  const run = await gate.runAttempts(newSession, { attempts: 3, delayMs: 0, log: () => {} });
  check('every attempt gets its own browsing context', opened.length === 3 && closed === 3, `${opened.length} opened, ${closed} closed`);
  check('no two attempts share a page', new Set(opened.map((s) => s.page)).size === opened.length);
  check('a run that reads nothing reports failure', run.ok === false && run.problems.length > 0, JSON.stringify(run.problems));
}
// What a deployment with nothing wrong with it answers, so the cases below differ only in what
// happens to the CONTEXT.
const healthyFetch = async (u) => {
  const path = u.split('?')[0];
  const body = path.endsWith('/importmap.json')
    ? JSON.stringify({ imports: { '@openmrs/esm-chartsearchai-app': './x/app.js' } })
    : path.endsWith('/routes.registry.json')
      ? '{"chartsearchai":true}'
      : path.endsWith('.sha')
        ? SHA
        : '';
  return { status: 200, text: async () => body, headers: { get: (h) => (h === 'last-modified' ? STAMP : null) } };
};
{
  const opened = [];
  let closed = 0;
  const newSession = async () => {
    const session = {
      page: { route: async () => {}, goto: async () => {}, evaluate: async (fn, arg) => fn(arg) },
      close: async () => { closed++; },
    };
    opened.push(session);
    return session;
  };
  globalThis.fetch = healthyFetch;
  const run = await gate.runAttempts(newSession, { attempts: 10, delayMs: 0, log: () => {} });
  check('a healthy deployment passes on the first attempt', run.ok === true && run.attempt === 1, JSON.stringify(run.problems));
  check('a healthy run opens exactly one context', opened.length === 1 && closed === 1, `${opened.length} opened, ${closed} closed`);
  check(
    'the healthy run carries out what it read, so the OK line is not written on trust',
    run.served && run.served.sha === SHA.slice(0, 12) && run.served.provenanceCompared === true,
    JSON.stringify(run.served),
  );
}
{
  // A context that fails to OPEN is the same transient the loop exists for. Opened outside the
  // try it took the whole gate down with it on the first bad attempt.
  let opens = 0;
  let closed = 0;
  const newSession = async () => {
    opens++;
    if (opens === 1) throw new Error('browser.newContext: Target closed\nsecond line');
    return {
      page: { route: async () => {}, goto: async () => {}, evaluate: async (fn, arg) => fn(arg) },
      close: async () => { closed++; },
    };
  };
  globalThis.fetch = healthyFetch;
  const run = await gate.runAttempts(newSession, { attempts: 3, delayMs: 0, log: () => {} });
  check('a context that fails to open is retried, not fatal', run.ok === true && run.attempt === 2, JSON.stringify(run.problems));
  check('a context that never opened is not closed', closed === 1, `${closed} closed`);
}
{
  // Closed even when the attempt throws: a run of failing attempts must not leak a context per
  // attempt, and the message stays one line for the same reason every other record here trims it.
  let closed = 0;
  const newSession = async () => ({
    page: {
      route: async () => {},
      goto: async () => {
        throw new Error('net::ERR_ABORTED\nsecond line');
      },
      evaluate: async (fn, arg) => fn(arg),
    },
    close: async () => { closed++; },
  });
  const run = await gate.runAttempts(newSession, { attempts: 2, delayMs: 0, log: () => {} });
  check('a context is closed even when the attempt throws', closed === 2, `${closed} closed`);
  check(
    'a failed navigation is reported on one line',
    has(run.problems, 'probe failed') && !run.problems.join('').includes('second line'),
    JSON.stringify(run.problems),
  );
}

console.log(failed === 0 ? '\nall gate self-tests passed' : `\n${failed} gate self-test(s) FAILED`);
process.exit(failed === 0 ? 0 : 1);
