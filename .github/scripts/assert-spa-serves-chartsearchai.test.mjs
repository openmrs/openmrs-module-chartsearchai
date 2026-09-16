// Self-test for the post-deploy gate's own logic.
//
// Why this exists. Every assertion the gate makes used to be executed for the first time against
// production: a wrong predicate would either wave a stale deploy through or fail every healthy
// one, and either way the first person to find out would be whoever read the red run. Two
// predicates in this gate's history were wrong in exactly those two directions — an equality test
// on Last-Modified that could never hold, and a hard comparison against a branch tip that nothing
// keeps the image in step with.
//
// It drives the REAL functions, imported from the gate with GATE_SELFTEST set so the browser
// block stays inert. `page` is stubbed to run the evaluated function in Node, and `fetch` is
// stubbed per case, so `probe` and `readHead` run their own code — including the cache-busting
// and the challenge poll — rather than a copy of it.
//
// Usage: GATE_SELFTEST=1 node .github/scripts/assert-spa-serves-chartsearchai.test.mjs

process.env.GATE_SELFTEST = '1';
process.env.GATE_CHALLENGE_MS = '50';
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
  check('an SPA-fallback stamp fails as not-a-sha', has(problems, 'did not return a commit sha'), problems.join('; '));
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
const page = { goto: async () => {}, evaluate: async (fn, arg) => fn(arg) };
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

console.log(failed === 0 ? '\nall gate self-tests passed' : `\n${failed} gate self-test(s) FAILED`);
process.exit(failed === 0 ? 0 : 1);
