// Post-deploy gate: assert the deployed SPA actually loads the Chart Search AI ESM.
//
// Why this exists. The deploy wrapper's exit code says the compose project was
// acted on, not that a clinician can see the feature. On 2026-08-25 the deploy
// was green and the backend module was loaded and healthy — drugreferencestatus
// reported 2283 entries — while the AI icon was absent from every patient
// chart, because a stale pre-compressed importmap (importmap.json.br, and .gz
// beside it) in the frontend image shadowed the assembled one and the browser
// never imported the ESM. Nothing in the deploy workflow could have said so.
//
// Why a browser rather than curl, and why HEADED. Three measurements, 2026-08-25:
//
//   1. The instance is behind Cloudflare, which answers a plain request with
//      403 and a managed challenge — on every path tried
//      (/openmrs/ws/rest/v1/session, /openmrs/spa/importmap.json,
//      /openmrs/index.htm), with and without a browser User-Agent. A
//      curl-based gate cannot reach the origin, so it would assert nothing
//      while looking green.
//   2. HEADLESS Chromium is challenged too: the page never leaves the
//      interstitial (title stays "Loading …") and an in-page fetch throws.
//      Headed Chromium cleared the challenge and read the importmap at t+10s.
//      So this launches headed and CI runs it under xvfb.
//   3. The regression itself is only visible to a client that asks for a
//      compressed response. The frontend image's nginx serves <file>.br or
//      <file>.gz when one exists, trying .br first; a plain curl sends no
//      Accept-Encoding, and measured directly against the image it read the
//      CORRECT plain importmap while both compressed siblings were stale.
//      Every browser asks for br and gzip; so does fetch() here.
//
// A second failure class, measured 2026-09-15 and the reason for the sha stamp below. A green
// build and a green deploy left the served ESM directory holding files from TWO builds — the
// numbered chunks from the current one, the ENTRY bundle the importmap names from a build 11
// hours older. Chunk ids are per-build, so the old entry requested the old build's chunks and
// the new ones were never loaded; the merged feature did not render. Everything this gate
// checked was correct at the time, and the entry is byte-identical in size across those two
// builds (83,245), so nothing comparing names or sizes could see it. Hence: ask the deployment
// which commit it is serving, and whether the entry pre-dates that build.
//
// This reads only files nginx serves statically, so it does not wait on
// OpenMRS's own startup, which can run to 30 minutes on a first boot.
//
// Usage: xvfb-run -a node assert-spa-serves-chartsearchai.mjs [baseUrl]

// Imported inside the run block, not here, so the self-test beside this file can drive `probe`,
// `readHead`, `entryUrlFrom` and `problemsWith` without playwright installed at all.

const BASE = (process.argv[2] || 'https://chartsearchai.openmrs.org').replace(/\/+$/, '');
const ESM = '@openmrs/esm-chartsearchai-app';
const IMPORTMAP = '/openmrs/spa/importmap.json';
const ROUTES = '/openmrs/spa/routes.registry.json';
// Written by Dockerfile.frontend from `git rev-parse HEAD` of the ESM clone, so the deployment
// can be asked WHICH commit it is serving rather than only whether a file exists.
const ESM_SHA = '/openmrs/spa/chartsearchai-esm.sha';

// The ESM's main tip, resolved by deploy.yml. Reported, never failed on — and that is a
// deliberate demotion, not laziness. Nothing rebuilds the frontend image when the ESM repo
// moves: build-docker.yml triggers on pushes to THIS repo and on workflow_dispatch, and there is
// no schedule, workflow_run or repository_dispatch watching the other one. So between an ESM
// merge and the next module-repo push, the live image is legitimately behind that tip, and a
// gate that failed on it would fail every deploy while no fresher image existed — sending
// whoever read it to the host for a problem that was not there.
//
// What this leaves uncovered, stated rather than papered over: an image that is wholly stale but
// self-consistent passes both hard checks below. Closing that needs the expectation to come from
// the registry (the ESM sha the live tag was built from) rather than from a branch tip, which is
// a bigger change than this one.
const EXPECTED_SHA = (process.env.ESM_EXPECTED_SHA || '').trim();

// Overridable so the gate can be exercised without waiting out the full poll.
// Validated, not merely parsed: `Number('soon')` is NaN, and a NaN deadline makes
// `Date.now() > deadline` false forever — the read loop then spins until the job's own timeout
// and surfaces as a CANCELLED run rather than a red gate. Four such names were added by the
// commit that removed the GATE_SELFTEST kill switch; this is the same family.
const positiveNumber = (name, fallback) => {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) {
    console.error(`${name}=${JSON.stringify(raw)} is not a positive number; using ${fallback}`);
    return fallback;
  }
  return n;
};
const ATTEMPTS = positiveNumber('GATE_ATTEMPTS', 10);
const DELAY_MS = positiveNumber('GATE_DELAY_MS', 30_000);
const CHALLENGE_MS = positiveNumber('GATE_CHALLENGE_MS', 60_000);
const POLL_MS = positiveNumber('GATE_POLL_MS', 2_000);

// Every read gets a unique URL, and UNIQUENESS is the point rather than mere presence: a
// constant buster is one URL an edge can cache forever, which is the failure the busting exists
// to prevent. Date.now() alone is not enough — it is millisecond-resolution, and two reads in
// the same millisecond produced the same value, so a counter carries it.
//
// The counter advances by 100 per CALL, which is headroom for the usual case rather than a bound:
// a CHALLENGED read consumes one increment per poll per path, so a probe of three paths polling
// to a 60s deadline at 2s intervals takes about 93, and more if the interval is shortened. Seeds
// can therefore overlap under sustained challenge; the `Date.now()` half carries uniqueness
// there, which is why both are in the URL. It only SEEDS the page-side functions below, which
// cannot close over module scope:
// they are serialised into the browser, so anything they use must be passed in. (Under a stub
// that runs them in Node they would see module scope and the divergence would go unnoticed —
// which is its own reason to pass it explicitly.)
let reads = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Reads the files that decide whether the ESM is loaded at all, and the stamp saying which
 * commit built it.
 *
 * The Cloudflare challenge is waited out by polling for a response that is not
 * a 403, rather than by sleeping a fixed interval — a fixed sleep either
 * over-waits on every healthy run or reports the challenge as an outage.
 */
async function probe(page, paths) {
  await page.goto(`${BASE}/openmrs/spa/login`, { waitUntil: 'domcontentloaded', timeout: 120_000 });
  return page.evaluate(
    async ({ paths, challengeMs, pollMs, bustSeed }) => {
      let n = bustSeed;
      const read = async (path) => {
        const deadline = Date.now() + challengeMs;
        // Unique per read, for the same reason readHead does it: `cache: 'no-store'` sends no
        // request header, so an edge cache may answer. Extension defaults are only one way
        // Cloudflare caches — an origin `Cache-Control` or a cache-everything rule reaches the
        // stamp too, and an edge-stale stamp reads OLDER than it is, which flips the provenance
        // comparison to a false GREEN on exactly the failure this gate exists for.
        for (;;) {
          // Per ATTEMPT, not per read: a challenged read used to re-fetch one identical URL.
          const bust = `${path}${path.includes('?') ? '&' : '?'}cb=${Date.now()}-${++n}`;
          try {
            const r = await fetch(bust, { cache: 'no-store' });
            if (r.status !== 403 || Date.now() > deadline) {
              return {
                status: r.status,
                encoding: r.headers.get('content-encoding'),
                lastModified: r.headers.get('last-modified'),
                // So a comparison made against a CACHED stamp is visible in the output rather
                // than only in its conclusion.
                cache: r.headers.get('cf-cache-status'),
                age: r.headers.get('age'),
                body: await r.text(),
              };
            }
          } catch (e) {
            if (Date.now() > deadline) return { status: 0, encoding: null, body: '', error: e.message };
          }
          await new Promise((resolve) => setTimeout(resolve, pollMs));
        }
      };
      const out = {};
      for (const [key, path] of Object.entries(paths)) out[key] = await read(path);
      return out;
    },
    { paths, challengeMs: CHALLENGE_MS, pollMs: POLL_MS, bustSeed: (reads += 100) },
  );
}

/**
 * Reads one asset's headers. Used for the ESM entry bundle, whose URL is only known after the
 * importmap has been parsed, so it cannot join the fixed probe above.
 */
async function readHead(page, url) {
  return page.evaluate(
    async ({ u, challengeMs, pollMs, bustSeed }) => {
      let n = bustSeed;
      // `cache: 'no-store'` is a BROWSER-cache directive and sends no request header, so an edge
      // cache may still answer. The entry is a `.js`, which is in Cloudflare's default cacheable
      // set, while the `.sha` stamp is not — an edge-cached entry compared against an
      // origin-fresh stamp is exactly the comparison this gate must not get wrong, and the retry
      // loop cannot clear an edge cache. So the URL is made unique per read.
      // Polls out a 403 the same way the fixed probe does. Without it a transient challenge on
      // this one asset reads as "the importmap names a file that is not served", which is a
      // different and much more alarming failure than the one that happened.
      const deadline = Date.now() + challengeMs;
      for (;;) {
        // Inside the loop, as in probe. Above it, a challenged read re-fetched ONE identical
        // URL for the whole poll — an edge-cached 403 would then be polled to the deadline and
        // returned as "could not be read at all", i.e. RED on a healthy deployment, and on the
        // more cacheable side of the comparison at that.
  const bust = `${u}${u.includes('?') ? '&' : '?'}cb=${Date.now()}-${++n}`;
        try {
          const r = await fetch(bust, { cache: 'no-store' });
          if (r.status !== 403 || Date.now() > deadline) {
            return {
              url: u,
              status: r.status,
              lastModified: r.headers.get('last-modified'),
              // The entry is the MORE cacheable side of the comparison (a default-cacheable
              // `.js`), so leaving it without cache visibility would blind the output on the
              // likelier half.
              cache: r.headers.get('cf-cache-status'),
              age: r.headers.get('age'),
            };
          }
        } catch (e) {
          if (Date.now() > deadline) {
            return { url: u, status: 0, lastModified: null, cache: null, age: null, error: e.message.split('\n')[0] };
          }
        }
        await new Promise((resolve) => setTimeout(resolve, pollMs));
      }
    },
    { u: url, challengeMs: CHALLENGE_MS, pollMs: POLL_MS, bustSeed: (reads += 100) },
  );
}

/**
 * The importmap entry for the ESM, resolved against the SPA base, or null.
 *
 * `pathname` on purpose: the gate reads everything same-origin through the page that cleared the
 * challenge, so a specifier naming another host would be read from THIS one. That is a wrong
 * answer rather than a missing one, so it is refused below instead.
 */
function entryUrlFrom(importmapBody) {
  try {
    const specifier = (JSON.parse(importmapBody).imports || {})[ESM];
    if (!specifier) return null;
    const resolved = new URL(specifier, `${BASE}/openmrs/spa/`);
    if (resolved.origin !== new URL(BASE).origin) return { foreign: resolved.href };
    return resolved.pathname;
  } catch {
    return null;
  }
}

/**
 * Splits what it finds: `problems` fail the gate, `warnings` are printed and do not. The only
 * warning is the ESM-tip comparison — see EXPECTED_SHA for why it cannot be a failure.
 */
function problemsWith({ importmap, routes, esmSha }, entryHead) {
  const problems = [];
  const warnings = [];
  const enc = (r) => `content-encoding: ${r.encoding ?? 'none'}`;

  if (importmap.status !== 200) {
    problems.push(`${IMPORTMAP} returned HTTP ${importmap.status}${importmap.error ? ` (${importmap.error})` : ''}`);
  } else {
    let names;
    try {
      names = Object.keys(JSON.parse(importmap.body).imports || {});
    } catch {
      problems.push(`${IMPORTMAP} was not parseable JSON (${enc(importmap)})`);
    }
    if (names && !names.includes(ESM)) {
      problems.push(`${IMPORTMAP} names ${names.length} modules and none of them is ${ESM} (${enc(importmap)})`);
    }
  }

  if (routes.status !== 200) {
    problems.push(`${ROUTES} returned HTTP ${routes.status}${routes.error ? ` (${routes.error})` : ''}`);
  } else if (!routes.body.includes('chartsearchai')) {
    problems.push(`${ROUTES} names no chartsearchai route (${enc(routes)})`);
  }

  // Which commit is being served, and is the whole assembly from one build?
  //
  // Both checks exist because of 2026-09-15: the importmap named the ESM, both files above were
  // present and correct, the deploy and this gate were green — and the browser ran pre-merge
  // code, because the served directory mixed two builds and the ENTRY bundle was the older one.
  // Chunk ids are per-build, so an old entry requests the old build's chunks and the new ones are
  // never loaded. Asserting that a named file exists cannot see this; asserting provenance can.
  if (esmSha.status !== 200) {
    problems.push(
      `${ESM_SHA} returned HTTP ${esmSha.status}${esmSha.error ? ` (${esmSha.error})` : ''}` +
        ' — an image built before this stamp existed, so its age cannot be checked',
    );
  } else {
    const served = esmSha.body.trim();
    const looksLikeSha = /^[0-9a-f]{40}$/.test(served);
    if (!looksLikeSha) {
      // Not a redundant belt to the status check above: an SPA `try_files` fallback answers a
      // MISSING file with index.html and HTTP 200, so an image built before this stamp existed
      // shows up here rather than as a 404. Either way it means the same thing.
      problems.push(
        `${ESM_SHA} did not return a commit sha but ${JSON.stringify(served.slice(0, 48))}` +
          ' — most likely an image built before the stamp existed, answered by the SPA fallback',
      );
    } else if (EXPECTED_SHA && served !== EXPECTED_SHA) {
      warnings.push(
        `serving ESM commit ${served.slice(0, 12)}; the ESM's main is at ${EXPECTED_SHA.slice(0, 12)}.` +
          ' Expected whenever the ESM has moved since the frontend image was last built — only a new' +
          ' image build changes it, and no deploy can.',
      );
    }

    // Is the entry the importmap names from THIS build?
    //
    // Guarded on the stamp being a real sha: without that, an SPA-fallback stamp (index.html,
    // HTTP 200) produced BOTH "did not return a commit sha" and a fabricated "the entry
    // pre-dates this build's stamp" computed against index.html's mtime — and the epilogue then
    // routed the reader to the build-mixing paragraph for a failure that was not happening.
    //
    // Not an equality test on Last-Modified, which would fail every healthy deploy: the stamp is
    // written by `git rev-parse` in the ESM stage, before `yarn build`, and `COPY --from`
    // preserves that mtime — so it is minutes OLDER than anything `openmrs assemble` wrote. What
    // holds for a single build is the ORDER: the stamp is the earliest artifact of its own build,
    // so an entry older than the stamp cannot have come from it.
    //
    // This works through the compressed variants a browser is actually served, because
    // precompress-spa.mjs stamps each sibling with its source's mtime (utimesSync). Measured:
    // with distinct mtimes, a stamp at 21:01 yields a .br at 21:01 and an entry at 21:09 a .br
    // at 21:09, so the comparison survives compression. Note what does NOT cover it —
    // Dockerfile.frontend's final-stage sibling guard lists importmap.json and
    // routes.registry.json only, i.e. neither of the two files compared here.
    //
    // Replayed against 2026-09-15's real values — an entry at Tue 15 Sep 10:05:20 GMT against a
    // stamp from the build that assembled at 21:01:32 (the stamp's own mtime is earlier still,
    // being the `git rev-parse` time) — this reports the entry as pre-dating the build.
    if (looksLikeSha && entryHead && entryHead.status === 200) {
      const entryAt = Date.parse(entryHead.lastModified ?? '');
      const stampAt = Date.parse(esmSha.lastModified ?? '');
      if (!Number.isFinite(entryAt) || !Number.isFinite(stampAt)) {
        // Reported rather than skipped. Skipping would drop the provenance check while the gate
        // still reported success — a guard silently doing nothing is the exact failure class this
        // whole check exists to catch, so losing it has to be loud.
        problems.push(
          'cannot compare build provenance: Last-Modified missing or unparseable' +
            ` (entry: ${JSON.stringify(entryHead.lastModified)}; stamp: ${JSON.stringify(esmSha.lastModified)})`,
        );
      } else if (entryAt < stampAt) {
        problems.push(
          `${entryHead.url} pre-dates this build's own stamp, so it is from an earlier build` +
            ` (entry: ${entryHead.lastModified}; stamp: ${esmSha.lastModified})`,
        );
      }
    }
  }

  // Two shapes an importmap can take that the name check above is satisfied by. They failed
  // differently, which is worth keeping straight: an EMPTY specifier resolved to null, so every
  // check below was skipped and the gate went confidently green; a FOREIGN one resolved to a
  // non-null pathname, so the checks ran — against this origin at the other host's path, which
  // is a wrong answer rather than a missing one. Contrived for this deployment; both are refused
  // because a confident green is the single outcome this script exists to stop being trusted.
  if (importmap.status === 200 && !problems.length) {
    const entry = entryUrlFrom(importmap.body);
    if (entry === null) {
      problems.push(`${IMPORTMAP} names ${ESM} but gives it no usable specifier`);
    } else if (typeof entry === 'object' && entry.foreign) {
      problems.push(`${IMPORTMAP} points ${ESM} at another origin (${entry.foreign}), which this gate cannot verify`);
    }
  }

  // Outside the stamp's branch on purpose: an entry the importmap names but nginx does not serve
  // is a failure whether or not the stamp exists to date it.
  //
  // 403 and 0 are told apart from a genuine 404: readHead polls a challenge out, but it gives up
  // after CHALLENGE_MS and returns the 403, and "the importmap names a file that is not served"
  // would then be both alarming and wrong.
  // A usable specifier whose entry was never read leaves every provenance check skipped while
  // the verdict stays green — loud-but-green, which is not what `a guard silently doing nothing
  // has to be loud` was for. Unreachable through the current caller (readHead returns an object
  // on both paths); asserted rather than trusted to that.
  if (typeof entryUrlFrom(importmap.status === 200 ? importmap.body : '{}') === 'string' && !entryHead) {
    problems.push('the entry bundle was never read, so nothing about its provenance was checked');
  }

  if (entryHead && entryHead.status !== 200) {
    const unreachable = entryHead.status === 403 || entryHead.status === 0;
    problems.push(
      `${entryHead.url} returned HTTP ${entryHead.status}${entryHead.error ? ` (${entryHead.error})` : ''}` +
        (unreachable
          ? ' — could not be read at all (challenge or network), so nothing about it was checked'
          : ' — the importmap names a file that is not served'),
    );
  }

  return { problems, warnings };
}

// Everything above is importable; the gate itself is below.
//
// The condition is ENTRY-POINT IDENTITY, deliberately, and not an environment variable. It was
// `if (process.env.GATE_SELFTEST)` for one commit, which made any non-empty value — '1', '0',
// 'false' — turn the gate into a no-op that printed nothing and exited 0. The name was invited
// into workflow scope by this file's own usage line and by deploy.yml's comment. (Not by a repo
// or org variable, which reach a step only through `${{ vars.X }}` and are never injected into
// the environment — the vector was a workflow- or job-level `env:`.) A script whose whole purpose
// is to stop a confident green being trusted would have shipped one behind an env var.
/** The gate's verdict. Named and exported so a test can pin it; see problemsWith. */
export const isHealthy = (problems) => problems.length === 0;

export { entryUrlFrom, problemsWith, probe, readHead };

// realpathSync, because `import.meta.url` is realpath-resolved by the loader while `process.argv[1]`
// is only path-resolved: any symlink appearing literally in the invocation path made these differ,
// and the gate then exited 0 having printed nothing. Reproduced three ways — the script symlinked,
// a symlinked PARENT directory, and `/tmp` on macOS — which is the same silent green the
// GATE_SELFTEST guard was removed for, reintroduced by its replacement.
//
// And the line printed on the other branch closes the CLASS rather than this instance: whatever
// makes this test false in future, a run that decides not to run says so.
const { pathToFileURL } = await import('node:url');
const { realpathSync } = await import('node:fs');
const entryHref = (() => {
  if (!process.argv[1]) return null;
  try {
    return pathToFileURL(realpathSync(process.argv[1])).href;
  } catch {
    return pathToFileURL(process.argv[1]).href;
  }
})();
const invokedDirectly = entryHref !== null && import.meta.url === entryHref;

if (!invokedDirectly) {
  console.error(
    `assert-spa-serves-chartsearchai: imported, NOT run (entry ${entryHref ?? 'unknown'}). ` +
      'If you expected the gate to run, this is the bug.',
  );
}

if (invokedDirectly) {
const { chromium } = await import('playwright');
const browser = await chromium.launch({ headless: false });
try {
  const page = await browser.newPage();
  let problems = ['the gate never completed a probe'];
  // The newest attempt's problems replace the older ones, so a transient error on the LAST
  // attempt would bury a real finding from the first and send the reader down the wrong
  // paragraph of the epilogue. Both are kept and both are printed.
  let firstProblems = null;
  let lastDiagnostics = null;

  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    let warnings = [];
    let served = null;
    try {
      const probed = await probe(page, { importmap: IMPORTMAP, routes: ROUTES, esmSha: ESM_SHA });
      const entry = probed.importmap.status === 200 ? entryUrlFrom(probed.importmap.body) : null;
      // Only a same-origin path is readable; a foreign or missing specifier is reported by
      // problemsWith instead of fetched.
      const entryHead = typeof entry === 'string' ? await readHead(page, entry) : null;
      ({ problems, warnings } = problemsWith(probed, entryHead));
      served = {
        sha: (probed.esmSha.body || '').trim().slice(0, 12) || null,
        stampAt: probed.esmSha.lastModified,
        stampCache: probed.esmSha.cache,
        entry: entryHead && entryHead.url,
        entryAt: entryHead && entryHead.lastModified,
        // Whether the comparison actually ran, asserted rather than inferred from a caller
        // invariant — the success line below says it ran, and must not say so on trust.
        provenanceCompared: Boolean(
          entryHead &&
            entryHead.status === 200 &&
            Number.isFinite(Date.parse(entryHead.lastModified ?? '')) &&
            Number.isFinite(Date.parse(probed.esmSha.lastModified ?? '')),
        ),
      };
      lastDiagnostics = served;
    } catch (e) {
      problems = [`probe failed: ${e.message.split('\n')[0]}`];
    }
    if (firstProblems === null && problems.length) firstProblems = problems;
    for (const w of warnings) console.log(`note: ${w}`);
    if (isHealthy(problems)) {
      // The whole premise here is that a green gate was once trusted wrongly, so say what was
      // actually established rather than only that it passed.
      console.log(`OK: ${BASE} serves ${ESM} (attempt ${attempt})`);
      if (served) {
        console.log(
          `  ESM commit served: ${served.sha ?? 'unknown'} (stamp ${served.stampAt ?? 'no Last-Modified'}` +
            `${served.stampCache ? `, cf-cache-status: ${served.stampCache}` : ''})`,
        );
        console.log(`  entry bundle: ${served.entry ?? 'not read'} (${served.entryAt ?? 'no Last-Modified'})`);
        console.log(`  ESM tip comparison: ${EXPECTED_SHA ? 'compared' : 'NOT resolved, so not compared'}`);
        console.log(
          served.provenanceCompared
            ? '  checked: the entry does not pre-date this build.'
            : '  NOT checked: the provenance comparison did not run (see above).',
        );
        console.log('  NOT checked either: whether the whole image is older than the registry, or');
        console.log('  whether a NEW entry sits beside OLD chunks — see this script\'s epilogue.');
      }
      process.exit(0);
    }
    console.log(`attempt ${attempt}/${ATTEMPTS}: ${problems.join('; ')}`);
    if (attempt < ATTEMPTS) await sleep(DELAY_MS);
  }

  console.error(`\nFAILED: ${BASE} does not load ${ESM}.`);
  for (const p of problems) console.error(`  - ${p}`);
  if (firstProblems && firstProblems.join('; ') !== problems.join('; ')) {
    console.error('\n  The FIRST attempt reported something different, which is the more likely');
    console.error('  diagnosis if what remains above looks transient:');
    for (const p of firstProblems) console.error(`  - ${p}`);
  }
  if (lastDiagnostics) {
    console.error(
      `\n  Served ESM commit: ${lastDiagnostics.sha ?? 'unknown'}` +
        ` (stamp ${lastDiagnostics.stampAt ?? 'no Last-Modified'}` +
        `${lastDiagnostics.stampCache ? `, cf-cache-status: ${lastDiagnostics.stampCache}` : ''})`,
    );
    console.error(
      `  Entry bundle: ${lastDiagnostics.entry ?? 'not read'} (${lastDiagnostics.entryAt ?? 'no Last-Modified'})`,
    );
  }
  console.error(
    [
      '',
      'The backend module can be installed and healthy and this still fails: the',
      'icon is drawn by the ESM, so an importmap that does not name it means the',
      'browser never loads it. Check that the frontend container runs',
      'openmrs/openmrs-reference-application-3-frontend:nightly-chartsearch, and',
      'that no stale importmap.json.br or .gz shadows the assembled',
      'importmap.json — Dockerfile.frontend guards the image against exactly',
      'that at build time.',
      '',
      'A provenance failure above means something different: the files are served,',
      'but not all from one build. The image on Docker Hub was verified consistent',
      'when this happened, so look at the host — `nightly-chartsearch` is a MUTABLE',
      'tag, and `docker compose up -d` recreates a service only when the resolved',
      'image ID changes, so a host that does not pull first keeps running whatever',
      'it cached. docker-compose.yml parameterises `${TAG:-nightly-chartsearch}`,',
      'and build-docker.yml now publishes `sha-<commit>` beside the moving tag. Read',
      'that tag for what it is: the MODULE commit, with the ESM re-cloned on every',
      'build, so a dispatch re-run at the same module commit re-publishes the same',
      'tag over different ESM content. Being re-publishable it is mutable too, so it',
      'narrows what a host can be running without pinning it — only a digest pins.',
      'Nothing in this repo sets TAG; doing so is a host-side change.',
      '',
      'Three directions this gate does NOT cover, so that a green run is not read for',
      'more than it says. A wholly stale but self-consistent image (see EXPECTED_SHA',
      'above). The mirror of the observed failure — a NEW entry beside OLD chunks,',
      'equally fatal, invisible because only the entry bundle is checked. And any',
      'mixing where the stamp travels with the OLDER half: the comparison is relative,',
      'so an old entry beside an old stamp and new chunks — the 2026-09-15 shape with',
      'the stamp on the other side — reads as consistent.',
    ].join('\n'),
  );
  process.exit(1);
} finally {
  await browser.close();
}
}
