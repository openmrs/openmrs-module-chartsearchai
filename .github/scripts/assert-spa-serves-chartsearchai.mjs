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

// The commit the deployment ought to be serving, resolved by the workflow (deploy.yml) so this
// script needs no GitHub access of its own. Left empty, the sha comparison is skipped and only
// the provenance check below runs — an older image predating the stamp then fails on the stamp
// being absent, which is the correct outcome and says so.
const EXPECTED_SHA = (process.env.ESM_EXPECTED_SHA || '').trim();

// Overridable so the gate can be exercised without waiting out the full poll.
const ATTEMPTS = Number(process.env.GATE_ATTEMPTS || 10);
const DELAY_MS = Number(process.env.GATE_DELAY_MS || 30_000);
const CHALLENGE_MS = Number(process.env.GATE_CHALLENGE_MS || 60_000);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Reads the two files that decide whether the ESM is loaded at all.
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
  return page.evaluate(async (u) => {
    try {
      const r = await fetch(u, { cache: 'no-store' });
      return { url: u, status: r.status, lastModified: r.headers.get('last-modified') };
    } catch (e) {
      return { url: u, status: 0, lastModified: null, error: e.message };
    }
  }, url);
}

/** The importmap entry for the ESM, resolved against the SPA base, or null. */
function entryUrlFrom(importmapBody) {
  try {
    const specifier = (JSON.parse(importmapBody).imports || {})[ESM];
    return specifier ? new URL(specifier, `${BASE}/openmrs/spa/`).pathname : null;
  } catch {
    return null;
  }
}

/** Returns the reasons the deployment is not serving the ESM; empty means healthy. */
function problemsWith({ importmap, routes, esmSha }, entryHead) {
  const problems = [];
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
      problems.push(`${ESM_SHA} is not a commit sha: ${JSON.stringify(served.slice(0, 60))}`);
    } else if (EXPECTED_SHA && served !== EXPECTED_SHA) {
      problems.push(
        `serving ESM commit ${served.slice(0, 12)} but ${EXPECTED_SHA.slice(0, 12)} is current on main` +
          ' — the frontend container is running an older image than the one just built',
      );
    }

    // Same assembly, so same mtime: Dockerfile.frontend copies the stamp in beside the assembled
    // output. A differing Last-Modified on the entry the importmap actually names means the
    // directory holds more than one build, whichever file happens to be newer.
    if (entryHead && entryHead.status === 200 && entryHead.lastModified && esmSha.lastModified) {
      if (entryHead.lastModified !== esmSha.lastModified) {
        problems.push(
          `${entryHead.url} is from a different build than ${ESM_SHA}` +
            ` (entry: ${entryHead.lastModified}; stamp: ${esmSha.lastModified})`,
        );
      }
    } else if (entryHead && entryHead.status !== 200) {
      problems.push(`${entryHead.url} returned HTTP ${entryHead.status} — the importmap names a file that is not served`);
    }
  }

  return problems;
}

const browser = await chromium.launch({ headless: false });
try {
  const page = await browser.newPage();
  let problems = ['the gate never completed a probe'];

  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    try {
      const probed = await probe(page, { importmap: IMPORTMAP, routes: ROUTES, esmSha: ESM_SHA });
      const entryPath = probed.importmap.status === 200 ? entryUrlFrom(probed.importmap.body) : null;
      problems = problemsWith(probed, entryPath ? await readHead(page, entryPath) : null);
    } catch (e) {
      problems = [`probe failed: ${e.message.split('\n')[0]}`];
    }
    if (problems.length === 0) {
      console.log(`OK: ${BASE} serves an importmap naming ${ESM} (attempt ${attempt})`);
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
      'A commit-sha or provenance failure above means something different: the',
      'files are served, but not all from the build that was just pushed. The',
      'image on Docker Hub has been verified consistent in that situation, so',
      'look at the host — `nightly-chartsearch` is a MUTABLE tag, and',
      '`docker compose up -d` recreates a service only when the resolved image',
      'ID changes, so a host that does not pull first keeps running whatever it',
      'cached. The per-commit `sha-<commit>` tags now published alongside it can',
      'be pinned via TAG, which docker-compose.yml already parameterises.',
    ].join('\n'),
  );
  process.exit(1);
} finally {
  await browser.close();
}
