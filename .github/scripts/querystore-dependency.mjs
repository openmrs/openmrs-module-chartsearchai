// Picks the QueryStore source that build.yml's `build-against-querystore-head` job builds: QueryStore
// main, or the open QueryStore pull request this pull request names on a Depends-On line.
//
// Why this exists. A chartsearchai change that needs QueryStore API which has not merged yet fails
// the required build, which compiles against the PUBLISHED querystore-api SNAPSHOT, and fails a
// build against QueryStore main for the same reason. Neither says whether the change builds
// against the code it actually needs. The job this replaced built a QueryStore commit pinned by
// SHA, and the pin was stranded twice when the QueryStore stack it pointed into was rebased
// (28f2082, then 118c608), each time leaving a green check about code no branch held any more.
// Resolving the pull request at run time follows its current head instead.
//
// The line, alone on its own line of the pull request description, in either form:
//
//   Depends-On: openmrs/openmrs-module-querystore#98
//   Depends-On: https://github.com/openmrs/openmrs-module-querystore/pull/98
//
// Name the top of a QueryStore stack: its head carries every layer below it. A Depends-On line for
// another repository is left alone. A QueryStore line in neither form, or two QueryStore lines
// naming different pull requests, fails the job rather than quietly building main.
//
// The description is read from the API when the job runs, not from the event that started it: a
// re-run replays the original event, so a line added after it would otherwise go unseen.
//
// Usage (the job sets these): PR_REPOSITORY, PR_NUMBER (empty outside pull requests: main),
// GITHUB_TOKEN, GITHUB_OUTPUT, GITHUB_STEP_SUMMARY.
//   node .github/scripts/querystore-dependency.mjs
// Self-test: node .github/scripts/querystore-dependency.test.mjs

import { appendFileSync, realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const REPOSITORY = 'openmrs/openmrs-module-querystore';
const DIRECTIVE = /^\s*depends-on:\s*(.*?)\s*$/i;
const QUERYSTORE_LINK = /^(?:openmrs\/openmrs-module-querystore#|https:\/\/github\.com\/openmrs\/openmrs-module-querystore\/pull\/)([1-9]\d*)\/?$/i;

// The QueryStore pull request number the description depends on, or null when it names none.
export function parseDependsOn(body) {
  const numbers = new Set();
  for (const line of (body ?? '').split(/\r?\n/)) {
    const directive = DIRECTIVE.exec(line);
    if (!directive || !/openmrs-module-querystore/i.test(directive[1])) continue;
    const link = QUERYSTORE_LINK.exec(directive[1]);
    if (!link) {
      throw new Error(
        `Depends-On line '${line.trim()}' names QueryStore but is not ` +
          `'${REPOSITORY}#<number>' or 'https://github.com/${REPOSITORY}/pull/<number>'.`,
      );
    }
    numbers.add(Number(link[1]));
  }
  if (numbers.size > 1) {
    throw new Error(
      `The description depends on QueryStore #${[...numbers].join(' and #')}; name one, the top of the stack.`,
    );
  }
  return numbers.size ? [...numbers][0] : null;
}

// The checkout ref for the QueryStore pull request (empty means the default branch) and a line
// saying which source that is. fetchPull(number) resolves to the REST pull request object.
export async function resolveSource(number, fetchPull) {
  if (number === null) {
    return { ref: '', summary: 'QueryStore main: the description has no QueryStore Depends-On line.' };
  }
  const pull = await fetchPull(number);
  if (pull.merged) {
    return { ref: '', summary: `QueryStore main: Depends-On names #${number}, which has merged.` };
  }
  if (pull.state !== 'open') {
    throw new Error(`Depends-On names QueryStore #${number}, which was closed without merging.`);
  }
  return {
    ref: `refs/pull/${number}/head`,
    summary: `QueryStore #${number} (open), head ${pull.head.sha}, named by Depends-On.`,
  };
}

// The pull request's description as it reads now, or '' when there is no pull request (null).
export async function currentDescription(number, fetchPull) {
  if (number === null) return '';
  return (await fetchPull(number)).body ?? '';
}

// The pull request number in PR_NUMBER, or null when it is empty.
export function pullNumber(value) {
  if (!value) return null;
  if (!/^[1-9]\d*$/.test(value)) throw new Error(`PR_NUMBER '${value}' is not a pull request number.`);
  return Number(value);
}

export function githubPullFetcher(repository, token, fetchImpl = fetch) {
  return async (number) => {
    const url = `https://api.github.com/repos/${repository}/pulls/${number}`;
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'chartsearchai-ci' };
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetchImpl(url, { headers });
    if (response.status !== 200) {
      throw new Error(`Reading ${repository}#${number} from ${url} returned HTTP ${response.status}.`);
    }
    return response.json();
  };
}

const entryHref = (() => {
  if (!process.argv[1]) return null;
  try {
    return pathToFileURL(realpathSync(process.argv[1])).href;
  } catch {
    return pathToFileURL(process.argv[1]).href;
  }
})();

if (entryHref !== null && import.meta.url === entryHref) {
  try {
    const token = process.env.GITHUB_TOKEN;
    const description = await currentDescription(
      pullNumber(process.env.PR_NUMBER),
      githubPullFetcher(process.env.PR_REPOSITORY, token),
    );
    const source = await resolveSource(parseDependsOn(description), githubPullFetcher(REPOSITORY, token));
    console.log(source.summary);
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `ref=${source.ref}\n`);
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${source.summary}\n`);
  } catch (error) {
    console.log(`::error::${error.message}`);
    process.exit(1);
  }
}
