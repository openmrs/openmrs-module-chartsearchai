// Post-deploy gate: assert the OpenMRS backend STARTS and STAYS UP after a deploy.
//
// Why this exists. On 2026-11-03 run 41699816800 was green while the instance was unreachable.
// The deploy runs with --no-health-check, and the SPA gate beside this file reads only files nginx
// serves statically. It passed at 19:10:16Z. By 19:24Z Cloudflare answered 523 ("Origin is
// unreachable") on every path, the static SPA files included. Nothing in the workflow looked at
// the backend, or looked a second time.
//
// What it reads. `/openmrs/health/started` is answered by core's StartupFilter ahead of every other
// filter: 200 once `Listener.isOpenmrsStarted()`, 503 until then, and no body either way. So it
// needs no credentials, and a fetch() of it never loads HTML, which is what arms Cloudflare's
// JS-detection probe (the SPA gate's header, measurement 4).
//
// What it establishes, and what it does not. OpenMRS finished starting (modules included), and then
// kept answering 200 for SOAK_MS. It does NOT establish that chartsearchai answers a question, or
// that the host survives past the soak.
//
// Usage: xvfb-run -a node assert-backend-started.mjs [baseUrl]

const BASE = (process.argv[2] || 'https://chartsearchai.openmrs.org').replace(/\/+$/, '');
const HEALTH = '/openmrs/health/started';
// See the SPA gate's ORIGIN_DOC: fulfilled in the browser, never requested from the deployment.
const ORIGIN_DOC = `${BASE}/__chartsearchai-backend-gate-origin`;

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
// A first boot after --destroy-volumes "can run to 30 minutes" (the SPA gate's header); 45 leaves
// headroom so a slow legitimate boot is not a red run.
const DEADLINE_MS = positiveNumber('BACKEND_DEADLINE_MS', 45 * 60_000);
const SOAK_MS = positiveNumber('BACKEND_SOAK_MS', 5 * 60_000);
const INTERVAL_MS = positiveNumber('BACKEND_INTERVAL_MS', 20_000);
// The gateway is nginx and does not wait on the backend, so once it has started, a 52x from
// Cloudflare is not "still booting". It is tolerated this long, to cover the recreate itself.
const UNREACHABLE_MS = positiveNumber('BACKEND_UNREACHABLE_MS', 5 * 60_000);
const CHALLENGE_MS = positiveNumber('BACKEND_CHALLENGE_MS', 30_000);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * What one read says about the backend.
 *
 * - `started`: 200 from StartupFilter. A 200 with an HTML body is NOT this. That would be some
 *   other handler answering the path (a misrouted gateway, an SPA fallback), which proves nothing.
 * - `starting`: 503 from StartupFilter, or 502/504 from the gateway while Tomcat is not yet
 *   listening.
 * - `origin-unreachable`: Cloudflare's own 520-527, so neither the gateway nor the host answered.
 * - `challenged`: a 403 Cloudflare marked as its own refusal. It is no evidence either way.
 * - `network`: the fetch threw. No evidence either way.
 * - `missing`: 404. This core does not have the endpoint, so the gate cannot work and says so at once.
 * - `unexpected`: anything else.
 */
function classify(r) {
  if (r.status === 0) return 'network';
  if (r.status === 200) return /^\s*</.test(r.body || '') ? 'unexpected' : 'started';
  if (r.status === 503 || r.status === 502 || r.status === 504) return 'starting';
  if (r.status >= 520 && r.status <= 527) return 'origin-unreachable';
  if (r.status === 403 && r.mitigated) return 'challenged';
  if (r.status === 404) return 'missing';
  return 'unexpected';
}

const minutes = (ms) => `${(ms / 60_000).toFixed(1)} min`;

/**
 * Polls `read` until the backend has started and then stayed started for `soakMs`, or until one of
 * the failure conditions holds. It returns the verdict and prints nothing except the per-read
 * `log` line, so the caller owns the `OK: ` line deploy.yml greps for.
 *
 * `read`, `now` and `wait` are parameters so the self-test drives this loop rather than a copy of it.
 */
async function runGate({ read, now = Date.now, wait = sleep, log = console.log, deadlineMs, soakMs, intervalMs, unreachableMs }) {
  const start = now();
  let startedAt = null;
  let unreachableSince = null;
  let last = null;

  for (let n = 1; ; n++) {
    const r = await read();
    const kind = classify(r);
    const t = now();
    last = { ...r, kind };
    log(
      `read ${n} at +${minutes(t - start)}: HTTP ${r.status} → ${kind}` +
        `${r.mitigated ? ` (cf-mitigated: ${r.mitigated})` : ''}${r.error ? ` (${r.error})` : ''}`,
    );

    if (kind === 'missing') {
      return { ok: false, reason: `${HEALTH} returned 404, so this core has no startup health endpoint and the gate cannot judge it`, last };
    }

    if (startedAt === null) {
      if (kind === 'started') {
        startedAt = t;
        unreachableSince = null;
        log(`  started after ${minutes(t - start)}; now checking it stays up for ${minutes(soakMs)}`);
      } else {
        unreachableSince = kind === 'origin-unreachable' ? (unreachableSince ?? t) : null;
        if (unreachableSince !== null && t - unreachableSince >= unreachableMs) {
          return {
            ok: false,
            reason: `Cloudflare could not reach the origin for ${minutes(t - unreachableSince)} (HTTP ${r.status}). The gateway or the host is down, not merely booting`,
            last,
          };
        }
        if (t - start >= deadlineMs) {
          return { ok: false, reason: `OpenMRS had not started after ${minutes(t - start)} (last: HTTP ${r.status}, ${kind})`, last };
        }
      }
    } else if (kind === 'started') {
      if (t - startedAt >= soakMs) return { ok: true, startedAfterMs: startedAt - start, soakedMs: t - startedAt, reads: n, last };
    } else if (kind !== 'challenged' && kind !== 'network') {
      // Went down after starting. This is the 2026-11-03 shape, and it is the reason the gate soaks.
      return {
        ok: false,
        reason: `OpenMRS started at +${minutes(startedAt - start)}, then answered HTTP ${r.status} (${kind}) ${minutes(t - startedAt)} later`,
        last,
      };
    } else if (t - start >= deadlineMs + soakMs) {
      // Started once, but the soak could not be finished because every later read was refused or
      // threw. That is no evidence it stayed up, so it is not a pass.
      return { ok: false, reason: `started, but the soak could not be completed: reads stopped answering (${kind})`, last };
    }

    await wait(intervalMs);
  }
}

/**
 * One read of the health endpoint, from a browsing context of its own. Cloudflare's verdict
 * attaches to the context (the SPA gate's runAttempts javadoc), so a context that was refused once
 * must not be reused. A challenge is polled out for up to `challengeMs` within the read.
 */
async function readOnce(browser, { challengeMs }) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.route(ORIGIN_DOC, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: '<!doctype html><title>backend gate</title>' }));
    await page.goto(ORIGIN_DOC, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    return await page.evaluate(
      async ({ path, challengeMs }) => {
        const deadline = Date.now() + challengeMs;
        for (let i = 0; ; i++) {
          // Unique per read, so an edge can never answer from a cached status.
          const url = `${path}?cb=${Date.now()}-${i}`;
          try {
            const r = await fetch(url, { cache: 'no-store' });
            if (r.status !== 403 || Date.now() > deadline) {
              return { status: r.status, mitigated: r.headers.get('cf-mitigated'), body: (await r.text()).slice(0, 200) };
            }
          } catch (e) {
            if (Date.now() > deadline) return { status: 0, mitigated: null, body: '', error: e.message.split('\n')[0] };
          }
          await new Promise((res) => setTimeout(res, 3_000));
        }
      },
      { path: HEALTH, challengeMs },
    );
  } catch (e) {
    return { status: 0, mitigated: null, body: '', error: e.message.split('\n')[0] };
  } finally {
    await context.close().catch(() => {});
  }
}

export { classify, runGate };

// Entry-point identity, not an env var, for the reason the SPA gate gives at the same spot.
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
    `assert-backend-started: imported, NOT run (entry ${entryHref ?? 'unknown'}). ` +
      'If you expected the gate to run, this is the bug.',
  );
}

if (invokedDirectly) {
  const { chromium } = await import('playwright');
  const browser = await chromium.launch({ headless: false });
  try {
    const result = await runGate({
      read: () => readOnce(browser, { challengeMs: CHALLENGE_MS }),
      deadlineMs: DEADLINE_MS,
      soakMs: SOAK_MS,
      intervalMs: INTERVAL_MS,
      unreachableMs: UNREACHABLE_MS,
    });
    if (result.ok) {
      // The `OK: ` prefix is a CONTRACT with deploy.yml's grep; rewording it disarms that check.
      console.log(
        `OK: ${BASE}${HEALTH} started after ${minutes(result.startedAfterMs)} and stayed up for ${minutes(result.soakedMs)}`,
      );
      console.log('  NOT checked: that chartsearchai answers a question, or that the host stays up after the soak.');
      process.exit(0);
    }
    console.error(`\nFAILED: ${result.reason}.`);
    console.error(
      [
        '',
        '  HTTP 520-527 come from Cloudflare and mean it could not connect to the origin at all:',
        '  the gateway container or the host is down. Look on the host at `docker ps -a`, the',
        '  gateway and backend logs since the deploy, and `dmesg` for the OOM killer.',
        '  HTTP 503 or 502 that never clears means the backend is up but OpenMRS never',
        '  finished starting. Read the backend container log for the module that failed.',
      ].join('\n'),
    );
    process.exit(1);
  } finally {
    await browser.close();
  }
}
