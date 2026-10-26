// Self-test for querystore-dependency.mjs, run by build.yml before the job relies on it.
//
// It drives the real exported functions: the description parser over the forms a description can
// take, and the resolver with a stubbed GitHub API, so a wrong predicate fails here instead of
// quietly building QueryStore main. A last case runs the script as the job does, with no
// description, which must resolve to main without calling the API.
//
// Usage: node .github/scripts/querystore-dependency.test.mjs

import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { githubPullFetcher, parseDependsOn, resolveSource } from './querystore-dependency.mjs';

let failed = 0;
const check = (name, ok, detail) => {
  if (!ok) failed++;
  console.log(`${ok ? 'ok   ' : 'FAIL '} ${name}${detail ? ` — ${detail}` : ''}`);
};
const throws = (fn, fragment) => {
  try {
    fn();
    return false;
  } catch (error) {
    return error.message.includes(fragment);
  }
};
const rejects = async (promise, fragment) => {
  try {
    await promise;
    return false;
  } catch (error) {
    return error.message.includes(fragment);
  }
};

// parseDependsOn
check('no description', parseDependsOn(undefined) === null);
check('description without the line', parseDependsOn('Adds a cache.\n\nTests: FooTest') === null);
check('short form', parseDependsOn('Summary\n\nDepends-On: openmrs/openmrs-module-querystore#98\n') === 98);
check('URL form', parseDependsOn('Depends-On: https://github.com/openmrs/openmrs-module-querystore/pull/96') === 96);
check('URL with a trailing slash', parseDependsOn('Depends-On: https://github.com/openmrs/openmrs-module-querystore/pull/96/') === 96);
check('CRLF and any case', parseDependsOn('Intro\r\n  depends-on:   OpenMRS/openmrs-module-querystore#98  \r\n') === 98);
check('another repository is left alone', parseDependsOn('Depends-On: openmrs/openmrs-core#5000') === null);
check('the same pull request twice', parseDependsOn('Depends-On: openmrs/openmrs-module-querystore#98\nDepends-On: https://github.com/openmrs/openmrs-module-querystore/pull/98') === 98);
check('a mention mid-sentence is not the line', parseDependsOn('This Depends-On: openmrs/openmrs-module-querystore#98 is prose') === null);
check(
  'two different pull requests fail',
  throws(() => parseDependsOn('Depends-On: openmrs/openmrs-module-querystore#96\nDepends-On: openmrs/openmrs-module-querystore#98'), '#96 and #98'),
);
check('a malformed QueryStore line fails', throws(() => parseDependsOn('Depends-On: openmrs-module-querystore 98'), 'is not'));
check('a pull request number of zero fails', throws(() => parseDependsOn('Depends-On: openmrs/openmrs-module-querystore#0'), 'is not'));
check('trailing text fails', throws(() => parseDependsOn('Depends-On: openmrs/openmrs-module-querystore#98 and #97'), 'is not'));

// resolveSource
const pulls = {
  98: { state: 'open', merged: false, head: { sha: 'a'.repeat(40) } },
  96: { state: 'closed', merged: true, head: { sha: 'b'.repeat(40) } },
  90: { state: 'closed', merged: false, head: { sha: 'c'.repeat(40) } },
};
const asked = [];
const stub = async (number) => {
  asked.push(number);
  return pulls[number];
};

const none = await resolveSource(null, stub);
check('no line builds main', none.ref === '' && none.summary.startsWith('QueryStore main'));
check('no line asks GitHub nothing', asked.length === 0);

const open = await resolveSource(98, stub);
check('an open pull request builds its head', open.ref === 'refs/pull/98/head');
check('the summary names the head commit', open.summary.includes('a'.repeat(40)), open.summary);

const merged = await resolveSource(96, stub);
check('a merged pull request builds main', merged.ref === '' && merged.summary.includes('#96, which has merged'));

check('a closed, unmerged pull request fails', await rejects(resolveSource(90, stub), 'closed without merging'));

// githubPullFetcher
let request;
const fetchOk = async (url, init) => {
  request = { url, init };
  return { status: 200, json: async () => pulls[98] };
};
const fetched = await githubPullFetcher('token-value', fetchOk)(98);
check('the fetcher reads the QueryStore pull request', request.url === 'https://api.github.com/repos/openmrs/openmrs-module-querystore/pulls/98');
check('the fetcher sends the token', request.init.headers.Authorization === 'Bearer token-value');
check('the fetcher returns the body', fetched === pulls[98]);
const fetchMissing = async () => ({ status: 404, json: async () => ({}) });
check('an HTTP error fails, naming the status', await rejects(githubPullFetcher('', fetchMissing)(99999), 'HTTP 404'));

// The script as the job runs it, with no description: main, without touching the network.
const script = fileURLToPath(new URL('./querystore-dependency.mjs', import.meta.url));
const scratch = mkdtempSync(join(tmpdir(), 'querystore-dependency-'));
const output = join(scratch, 'output');
const summary = join(scratch, 'summary');
const printed = execFileSync(process.execPath, [script], {
  env: { PATH: process.env.PATH, PR_BODY: '', GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary },
  encoding: 'utf8',
});
check('the script writes an empty ref for main', readFileSync(output, 'utf8') === 'ref=\n');
check('the script prints and records the source', printed.startsWith('QueryStore main') && readFileSync(summary, 'utf8').startsWith('QueryStore main'));

let exitCode = 0;
let stdout = '';
try {
  execFileSync(process.execPath, [script], {
    env: { PATH: process.env.PATH, PR_BODY: 'Depends-On: openmrs-module-querystore 98', GITHUB_OUTPUT: output },
    encoding: 'utf8',
  });
} catch (error) {
  exitCode = error.status;
  stdout = error.stdout;
}
check('the script fails a malformed line with an annotation', exitCode === 1 && stdout.startsWith('::error::'), stdout.trim());

if (failed) {
  console.log(`${failed} failed`);
  process.exit(1);
}
console.log('all passed');
