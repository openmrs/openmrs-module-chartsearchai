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
// Why a browser rather than curl, why HEADED, and where it may navigate. Three measurements on
// 2026-08-25, and a fourth on 2026-09-20:
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
//   4. The browser must not NAVIGATE to an HTML document. Measured 2026-09-20, the day this
//      gate reported nothing but refusals over a deployment that was healthy: Cloudflare
//      injects its JS-detection probe (/cdn-cgi/challenge-platform/.../jsd/main.js) into HTML
//      responses. Loading /openmrs/spa/login pulled it in, the browser posted its fingerprint
//      at t+1.59s, and from t+1.61s every request out of that browsing context answered 403
//      with `cf-mitigated: challenge` — the static files read here included. That is why the
//      failed run's FIRST attempt reported only the entry bundle: the fixed reads are issued
//      together and beat the verdict, while the entry's URL is not known until the importmap
//      has been parsed, so it is always the read that lands after them. Navigating to a
//      non-HTML path instead — the importmap — pulled ZERO /cdn-cgi/ requests; after it, 60
//      sequential reads in one session and 40 parallel in another all returned 200. This gate
//      does not even do that, because nginx's 404 page IS html: navigating to a real path
//      would arm the probe on exactly the broken deployments it exists to describe. It fulfils
//      its own document instead — see ORIGIN_DOC. And the verdict attaches to the CONTEXT
//      rather than to the address, a context opened seconds later from the same machine having
//      read the deployment fine, which is why runAttempts gives every attempt one of its own.
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
// `readHead`, `entryUrlFrom`, `problemsWith`, `pollSchedule` and `runAttempts` — the attempt loop
// included, which is where the context-per-attempt rule lives — without playwright installed at
// all.

const BASE = (process.argv[2] || 'https://chartsearchai.openmrs.org').replace(/\/+$/, '');
const ESM = '@openmrs/esm-chartsearchai-app';
const IMPORTMAP = '/openmrs/spa/importmap.json';
const ROUTES = '/openmrs/spa/routes.registry.json';
// Written by Dockerfile.frontend from `git rev-parse HEAD` of the ESM clone, so the deployment
// can be asked WHICH commit it is serving rather than only whether a file exists.
const ESM_SHA = '/openmrs/spa/chartsearchai-esm.sha';

// The document the gate reads from. It needs a same-origin browsing context and nothing else —
// every check below is a fetch() out of one — and it gets that WITHOUT asking the deployment for
// a page: the navigation is intercepted and fulfilled in the browser, so no request for this URL
// is ever sent and the path need not exist. Measurement 4 is why it may not be a page the
// deployment serves: any HTML it answered with would carry Cloudflare's JS-detection probe, and
// nginx's 404 page is HTML, so a deployment broken in the way this gate is FOR would be the one
// that blinded it.
const ORIGIN_DOC = `${BASE}/__chartsearchai-gate-origin`;
const ORIGIN_DOC_BODY = '<!doctype html><title>chartsearchai deploy gate</title>';

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

/**
 * The waits between the reads of one challenged path: `pollMs`, doubling, capped at a quarter of
 * the deadline so the window is filled rather than abandoned a third of the way into it.
 *
 * It exists so that the COUNT of reads a challenge costs is a property of this code rather than
 * of how long the deadline happens to be. Fixed-interval polling spent the whole deadline at
 * `pollMs`, and the gate ran one of those per path per attempt, for every attempt — a request
 * storm aimed at a host that had already said it would not answer. What the deadline goes on
 * bounding is the wall clock, which a schedule cannot; both are checked in the loops below.
 *
 * Terminates because `cap >= pollMs > 0`, so every iteration adds at least `pollMs` to the total.
 */
const pollSchedule = (challengeMs, pollMs) => {
  const cap = Math.max(pollMs, Math.floor(challengeMs / 4));
  const waits = [];
  let total = 0;
  for (let d = pollMs; total + d <= challengeMs; d = Math.min(d * 2, cap)) {
    waits.push(d);
    total += d;
  }
  return waits;
};

const POLL_WAITS = pollSchedule(CHALLENGE_MS, POLL_MS);

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
 * The navigation exists only to get a same-origin browsing context for the reads below; nothing
 * this gate checks needs the SPA to run, and the document it lands on is one the gate fulfils
 * itself — see ORIGIN_DOC. The handler must FULFIL and never continue: letting the request
 * through is the whole defect.
 *
 * The Cloudflare challenge is waited out by polling for a response that is not
 * a 403, rather than by sleeping a fixed interval — a fixed sleep either
 * over-waits on every healthy run or reports the challenge as an outage. The poll BACKS OFF, so
 * what bounds the reads it spends is the schedule, not the deadline.
 */
async function probe(page, paths) {
  await page.route(ORIGIN_DOC, (route) =>
    route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: ORIGIN_DOC_BODY }));
  await page.goto(ORIGIN_DOC, { waitUntil: 'domcontentloaded', timeout: 120_000 });
  return page.evaluate(
    async ({ paths, challengeMs, waits, bustSeed }) => {
      let n = bustSeed;
      const read = async (path) => {
        const deadline = Date.now() + challengeMs;
        // Unique per read: `cache: 'no-store'` sends no request header, so an edge may answer from
        // cache. The origin's own headers make the ENTRY the stale-prone side — the base image's
        // nginx sets `expires 1y` on `.js` and `no-cache, must-revalidate` on the plain-file
        // location the stamp falls into — so the realistic failure is a stale ENTRY reading NEWER
        // than it is, i.e. a false RED. Either way the comparison must not be made against an edge
        // copy, which is what the cache-status check below is for.
        for (let i = 0; ; i++) {
          // Per ATTEMPT, not per read: a challenged read used to re-fetch one identical URL.
          const bust = `${path}${path.includes('?') ? '&' : '?'}cb=${Date.now()}-${++n}`;
          try {
            const r = await fetch(bust, { cache: 'no-store' });
            if (r.status !== 403 || i >= waits.length || Date.now() > deadline) {
              return {
                status: r.status,
                encoding: r.headers.get('content-encoding'),
                lastModified: r.headers.get('last-modified'),
                // Cloudflare's own word for "I refused this client", carried so the verdict can
                // say whether a 403 is about the deployment at all.
                mitigated: r.headers.get('cf-mitigated'),
                // So a comparison made against a CACHED stamp is visible in the output rather
                // than only in its conclusion.
                cache: r.headers.get('cf-cache-status'),
                age: r.headers.get('age'),
                body: await r.text(),
              };
            }
          } catch (e) {
            if (i >= waits.length || Date.now() > deadline) {
              // Same record shape and the same one-line trim as readAssetHeaders': these drifted,
              // and a multi-line fetch error then wrapped across the one-line `attempt N/M:` output
              // while `lastModified` came back undefined here and null everywhere else.
              return {
                status: 0,
                encoding: null,
                lastModified: null,
                mitigated: null,
                cache: null,
                age: null,
                body: '',
                error: e.message.split('\n')[0],
              };
            }
          }
          await new Promise((resolve) => setTimeout(resolve, waits[i]));
        }
      };
      // Concurrent, not serial. Each read waits out its OWN challenge deadline, so three serial
      // reads cost three deadlines: measured 3050ms against a 1000ms deadline, and 1028ms when
      // only one of the three was challenged. This gate went from two probed paths to three, which
      // raised the per-attempt worst case from two deadlines to three before this was fixed.
      // Uniqueness of the busters survives: `++n` runs synchronously before each await.
      const out = {};
      await Promise.all(Object.entries(paths).map(async ([key, path]) => { out[key] = await read(path); }));
      return out;
    },
    { paths, challengeMs: CHALLENGE_MS, waits: POLL_WAITS, bustSeed: (reads += 100) },
  );
}

/**
 * Reads one asset's headers. Used for the ESM entry bundle, whose URL is only known after the
 * importmap has been parsed, so it cannot join the fixed probe above.
 */
async function readHead(page, url) {
  return page.evaluate(
    async ({ u, challengeMs, waits, bustSeed }) => {
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
      for (let i = 0; ; i++) {
        // Inside the loop, as in probe. Above it, a challenged read re-fetched ONE identical
        // URL for the whole poll — an edge-cached 403 would then be polled to the deadline and
        // returned as "could not be read at all", i.e. RED on a healthy deployment, and on the
        // more cacheable side of the comparison at that.
        const bust = `${u}${u.includes('?') ? '&' : '?'}cb=${Date.now()}-${++n}`;
        try {
          const r = await fetch(bust, { cache: 'no-store' });
          if (r.status !== 403 || i >= waits.length || Date.now() > deadline) {
            return {
              url: u,
              status: r.status,
              lastModified: r.headers.get('last-modified'),
              mitigated: r.headers.get('cf-mitigated'),
              // The entry is the MORE cacheable side of the comparison (a default-cacheable
              // `.js`), so leaving it without cache visibility would blind the output on the
              // likelier half.
              cache: r.headers.get('cf-cache-status'),
              age: r.headers.get('age'),
            };
          }
        } catch (e) {
          if (i >= waits.length || Date.now() > deadline) {
            return { url: u, status: 0, lastModified: null, mitigated: null, cache: null, age: null, error: e.message.split('\n')[0] };
          }
        }
        await new Promise((resolve) => setTimeout(resolve, waits[i]));
      }
    },
    { u: url, challengeMs: CHALLENGE_MS, waits: POLL_WAITS, bustSeed: (reads += 100) },
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
  // Appended to every status so a refused run says who refused it. On 2026-09-20 this gate
  // printed round after round of bare `returned HTTP 403`, then an epilogue about Docker Hub,
  // over a deployment that was serving the right files the whole time.
  const mitigation = (r) => (r.mitigated ? ` (cf-mitigated: ${r.mitigated})` : '');

  if (importmap.status !== 200) {
    problems.push(
      `${IMPORTMAP} returned HTTP ${importmap.status}${importmap.error ? ` (${importmap.error})` : ''}${mitigation(importmap)}`,
    );
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
    problems.push(`${ROUTES} returned HTTP ${routes.status}${routes.error ? ` (${routes.error})` : ''}${mitigation(routes)}`);
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
      `${ESM_SHA} returned HTTP ${esmSha.status}${esmSha.error ? ` (${esmSha.error})` : ''}${mitigation(esmSha)}` +
        ' — an image built before this stamp existed, so its age cannot be checked',
    );
  } else {
    const served = esmSha.body.trim();
    const looksLikeSha = /^[0-9a-f]{40}$/.test(served);
    if (!looksLikeSha) {
      // NOT the "image predates the stamp" case, which arrives as a 404 and is handled above: the
      // base image's SPA fallback (`location / { try_files /index.html =404; }`) is reachable only
      // for a DOTLESS uri, and this path has a dot. So this branch means the file exists and its
      // body is not a sha — truncation, a host-side swap, an edge mangling it — and the body is
      // printed because none of those has an obvious cause to name.
      problems.push(
        `${ESM_SHA} exists but its body is not a commit sha: ${JSON.stringify(served.slice(0, 48))}`,
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

  // A HIT on either side means the comparison was made against an edge copy, not the origin — the
  // unique `?cb=` defeats that under default Cloudflare config, but a zone set to ignore query
  // strings would serve one cached answer to every read. Collected and printed since the round
  // that added them; nothing branched on it, which made the one failure mode they exist to expose
  // invisible in the verdict.
  for (const [label, rec] of [
    [ESM_SHA, esmSha],
    [entryHead && entryHead.url, entryHead],
  ]) {
    if (!rec || !label) continue;
    if (rec.cache === 'HIT' || Number(rec.age) > 0) {
      warnings.push(
        `${label} was answered from an edge cache (cf-cache-status: ${rec.cache ?? 'none'},` +
          ` age: ${rec.age ?? 'none'}) — the provenance comparison may not reflect the origin`,
      );
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
      `${entryHead.url} returned HTTP ${entryHead.status}${entryHead.error ? ` (${entryHead.error})` : ''}${mitigation(entryHead)}` +
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

/**
 * Runs the attempts, each in a browsing context of its own, and returns the verdict without
 * printing it — the caller owns the output, including the `OK: ` line deploy.yml greps for.
 *
 * A context per attempt, not a page held across all of them. Cloudflare's mitigation attaches to
 * the browsing context: once this browser had failed the JS-detection probe, every read out of
 * that context was refused for as long as it lived, so attempts sharing one page were copies of
 * the same refusal and the loop was decoration. A fresh context is the one thing that recovers
 * from it — measured 2026-09-20, header measurement 4.
 *
 * `newSession` returns `{ page, close }`. It is a parameter rather than a browser handle so the
 * self-test can drive this loop, which is where the context-per-attempt rule lives.
 */
async function runAttempts(newSession, { attempts, delayMs, wait = sleep, log = console.log }) {
  let problems = ['the gate never completed a probe'];
  // The newest attempt's problems replace the older ones, so a transient error on the LAST
  // attempt would bury a real finding from the first and send the reader down the wrong
  // paragraph of the epilogue. Both are kept and both are printed.
  let firstProblems = null;
  let lastDiagnostics = null;

  for (let attempt = 1; attempt <= attempts; attempt++) {
    let warnings = [];
    // Opened inside the try: a context that fails to OPEN is the same transient the loop exists
    // for, and outside it the whole gate would die on one bad attempt instead of retrying.
    let session = null;
    try {
      session = await newSession();
      const probed = await probe(session.page, { importmap: IMPORTMAP, routes: ROUTES, esmSha: ESM_SHA });
      const entry = probed.importmap.status === 200 ? entryUrlFrom(probed.importmap.body) : null;
      // Only a same-origin path is readable; a foreign or missing specifier is reported by
      // problemsWith instead of fetched.
      const entryHead = typeof entry === 'string' ? await readHead(session.page, entry) : null;
      ({ problems, warnings } = problemsWith(probed, entryHead));
      lastDiagnostics = {
        sha: (probed.esmSha.body || '').trim().slice(0, 12) || null,
        stampAt: probed.esmSha.lastModified,
        stampCache: probed.esmSha.cache,
        entry: entryHead && entryHead.url,
        entryAt: entryHead && entryHead.lastModified,
        // Whether the comparison actually ran, asserted rather than inferred from a caller
        // invariant — the success line says it ran, and must not say so on trust.
        provenanceCompared: Boolean(
          entryHead &&
            entryHead.status === 200 &&
            Number.isFinite(Date.parse(entryHead.lastModified ?? '')) &&
            Number.isFinite(Date.parse(probed.esmSha.lastModified ?? '')),
        ),
      };
    } catch (e) {
      problems = [`probe failed: ${e.message.split('\n')[0]}`];
    } finally {
      // Reported, never fatal: a context this attempt is finished with failing to close must not
      // turn a healthy verdict red, and must not be silent either.
      try {
        if (session) await session.close();
      } catch (e) {
        log(`note: closing attempt ${attempt}'s browsing context failed: ${e.message.split('\n')[0]}`);
      }
    }
    if (firstProblems === null && problems.length) firstProblems = problems;
    for (const w of warnings) log(`note: ${w}`);
    if (isHealthy(problems)) return { ok: true, attempt, problems, firstProblems, served: lastDiagnostics };
    log(`attempt ${attempt}/${attempts}: ${problems.join('; ')}`);
    if (attempt < attempts) await wait(delayMs);
  }
  return { ok: false, attempt: attempts, problems, firstProblems, served: lastDiagnostics };
}

export { entryUrlFrom, problemsWith, probe, readHead, pollSchedule, runAttempts };

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
  const newSession = async () => {
    const context = await browser.newContext();
    return { page: await context.newPage(), close: () => context.close() };
  };
  const { ok, attempt, problems, firstProblems, served } = await runAttempts(newSession, {
    attempts: ATTEMPTS,
    delayMs: DELAY_MS,
  });

  if (ok) {
    // The whole premise here is that a green gate was once trusted wrongly, so say what was
    // actually established rather than only that it passed.
    // The `OK: ` prefix is a CONTRACT: deploy.yml greps for it so that a step exiting 0 without
    // reaching a verdict is red. Rewording this line disarms that check silently.
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
      console.log('  NOT checked: the directions this gate cannot see — enumerated in the failure');
      console.log('  epilogue below, and worth reading before treating this OK as "all is well".');
    }
    process.exit(0);
  }

  console.error(`\nFAILED: ${BASE} does not load ${ESM}.`);
  for (const p of problems) console.error(`  - ${p}`);
  if (firstProblems && firstProblems.join('; ') !== problems.join('; ')) {
    console.error('\n  The FIRST attempt reported something different, which is the more likely');
    console.error('  diagnosis if what remains above looks transient:');
    for (const p of firstProblems) console.error(`  - ${p}`);
  }
  if (served) {
    console.error(
      `\n  Served ESM commit: ${served.sha ?? 'unknown'}` +
        ` (stamp ${served.stampAt ?? 'no Last-Modified'}` +
        `${served.stampCache ? `, cf-cache-status: ${served.stampCache}` : ''})`,
    );
    console.error(
      `  Entry bundle: ${served.entry ?? 'not read'} (${served.entryAt ?? 'no Last-Modified'})`,
    );
    console.error(`  ESM tip comparison: ${EXPECTED_SHA ? 'compared' : 'NOT resolved, so not compared'}`);
  }
  console.error(
    [
      '',
      'A 403 on every path above is Cloudflare refusing this client, not a deployment',
      'fault — the messages carry `cf-mitigated` when that is what happened, and nothing',
      'on the host explains it. Header measurement 4 has the mechanism: any HTML this',
      'browser loads from the zone arms a JS-detection probe, and failing it refuses every',
      'later read out of that browsing context. This gate fulfils its own document to stay',
      'clear of that, so a refusal here means something else armed it.',
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
