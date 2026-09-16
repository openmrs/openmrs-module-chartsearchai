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

import { chromium } from 'playwright';

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
const ATTEMPTS = Number(process.env.GATE_ATTEMPTS || 10);
const DELAY_MS = Number(process.env.GATE_DELAY_MS || 30_000);
const CHALLENGE_MS = Number(process.env.GATE_CHALLENGE_MS || 60_000);

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
    async ({ paths, challengeMs }) => {
      const read = async (path) => {
        const deadline = Date.now() + challengeMs;
        for (;;) {
          try {
            const r = await fetch(path, { cache: 'no-store' });
            if (r.status !== 403 || Date.now() > deadline) {
              return {
                status: r.status,
                encoding: r.headers.get('content-encoding'),
                lastModified: r.headers.get('last-modified'),
                body: await r.text(),
              };
            }
          } catch (e) {
            if (Date.now() > deadline) return { status: 0, encoding: null, body: '', error: e.message };
          }
          await new Promise((resolve) => setTimeout(resolve, 2000));
        }
      };
      const out = {};
      for (const [key, path] of Object.entries(paths)) out[key] = await read(path);
      return out;
    },
    { paths, challengeMs: CHALLENGE_MS },
  );
}

/**
 * Reads one asset's headers. Used for the ESM entry bundle, whose URL is only known after the
 * importmap has been parsed, so it cannot join the fixed probe above.
 */
async function readHead(page, url) {
  return page.evaluate(
    async ({ u, challengeMs }) => {
      // `cache: 'no-store'` is a BROWSER-cache directive and sends no request header, so an edge
      // cache may still answer. The entry is a `.js`, which is in Cloudflare's default cacheable
      // set, while the `.sha` stamp is not — an edge-cached entry compared against an
      // origin-fresh stamp is exactly the comparison this gate must not get wrong, and the retry
      // loop cannot clear an edge cache. So the URL is made unique per read.
      const bust = `${u}${u.includes('?') ? '&' : '?'}cb=${Date.now()}`;
      // Polls out a 403 the same way the fixed probe does. Without it a transient challenge on
      // this one asset reads as "the importmap names a file that is not served", which is a
      // different and much more alarming failure than the one that happened.
      const deadline = Date.now() + challengeMs;
      for (;;) {
        try {
          const r = await fetch(bust, { cache: 'no-store' });
          if (r.status !== 403 || Date.now() > deadline) {
            return { url: u, status: r.status, lastModified: r.headers.get('last-modified') };
          }
        } catch (e) {
          if (Date.now() > deadline) return { url: u, status: 0, lastModified: null, error: e.message };
        }
        await new Promise((resolve) => setTimeout(resolve, 2000));
      }
    },
    { u: url, challengeMs: CHALLENGE_MS },
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
    if (!/^[0-9a-f]{40}$/.test(served)) {
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
    // Not an equality test on Last-Modified, which would fail every healthy deploy: the stamp is
    // written by `git rev-parse` in the ESM stage, before `yarn build`, and `COPY --from`
    // preserves that mtime — so it is minutes OLDER than anything `openmrs assemble` wrote. What
    // holds for a single build is the ORDER: the stamp is the earliest artifact of its own build,
    // so an entry older than the stamp cannot have come from it.
    //
    // This works through the compressed variants a browser is actually served, because
    // precompress-spa.mjs stamps each sibling with its source's mtime (utimesSync, and
    // Dockerfile.frontend's own guard relies on the same thing).
    //
    // Replayed against 2026-09-15's real values — entry Tue 15 Sep 10:05:20 GMT against a stamp
    // from the 21:01:32 assembly — this reports the entry as pre-dating the build. The opposite
    // skew, a whole directory consistently old, is caught by the sha comparison above instead.
    if (entryHead && entryHead.status === 200) {
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

  // An importmap that names the ESM with an empty or foreign specifier passed the whole gate:
  // `names.includes(ESM)` was true, the entry URL came back null, and every check below was
  // skipped. Contrived for this deployment, but it produced a CONFIDENT green, which is the one
  // outcome this script exists to stop being trusted.
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

const browser = await chromium.launch({ headless: false });
try {
  const page = await browser.newPage();
  let problems = ['the gate never completed a probe'];

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
        entry: entryHead && entryHead.url,
        entryAt: entryHead && entryHead.lastModified,
      };
    } catch (e) {
      problems = [`probe failed: ${e.message.split('\n')[0]}`];
    }
    for (const w of warnings) console.log(`note: ${w}`);
    if (problems.length === 0) {
      // The whole premise here is that a green gate was once trusted wrongly, so say what was
      // actually established rather than only that it passed.
      console.log(`OK: ${BASE} serves ${ESM} (attempt ${attempt})`);
      if (served) {
        console.log(`  ESM commit served: ${served.sha ?? 'unknown'} (stamp ${served.stampAt ?? 'no Last-Modified'})`);
        console.log(`  entry bundle: ${served.entry ?? 'not read'} (${served.entryAt ?? 'no Last-Modified'})`);
        console.log('  checked: the entry does not pre-date this build. NOT checked: whether the whole');
        console.log('  image is older than the registry — see EXPECTED_SHA in this script.');
      }
      process.exit(0);
    }
    console.log(`attempt ${attempt}/${ATTEMPTS}: ${problems.join('; ')}`);
    if (attempt < ATTEMPTS) await sleep(DELAY_MS);
  }

  console.error(`\nFAILED: ${BASE} does not load ${ESM}.`);
  for (const p of problems) console.error(`  - ${p}`);
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
      'and build-docker.yml now publishes `sha-<commit>` beside the moving tag, so',
      'an exact image can be pinned — but note what that tag does and does not say:',
      'it is the MODULE commit, and the ESM is re-cloned on every build, so a',
      'dispatch re-run at the same module commit re-publishes it with different ESM',
      'content. It pins an image, not an ESM revision.',
      '',
      'Two directions this gate does NOT cover, so that a green run is not read for',
      'more than it says: a wholly stale but self-consistent image (see EXPECTED_SHA',
      'above), and the mirror of the observed failure — a NEW entry beside OLD',
      'chunks, which is equally fatal and which only the entry bundle being checked',
      'leaves invisible.',
    ].join('\n'),
  );
  process.exit(1);
} finally {
  await browser.close();
}
