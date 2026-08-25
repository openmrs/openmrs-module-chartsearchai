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

/** Returns the reasons the deployment is not serving the ESM; empty means healthy. */
function problemsWith({ importmap, routes }) {
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

  return problems;
}

const browser = await chromium.launch({ headless: false });
try {
  const page = await browser.newPage();
  let problems = ['the gate never completed a probe'];

  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    try {
      problems = problemsWith(await probe(page, { importmap: IMPORTMAP, routes: ROUTES }));
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
    ].join('\n'),
  );
  process.exit(1);
} finally {
  await browser.close();
}
