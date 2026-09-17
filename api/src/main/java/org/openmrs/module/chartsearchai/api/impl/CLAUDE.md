# Rules for `api/impl` — talking to the local LLM subprocess

The root `CLAUDE.md` names this file and its rules bind this directory. Read it too; nothing here
replaces it. **This file is directives and pointers** — the evidence is `docs/adr.md` and javadoc,
which is the root file's "Documenting a decision" rule.

## The spawned llama-server

- **Every request to the spawned llama-server is built by `LlamaServerEndpoint.request`**, which
  attaches the secret minted for that server start. Take the URL from the same object —
  `completionsUrl`, `healthUrl`, `propsUrl`, `slotUrl`.
  **Nothing else may spell the loopback address or call `HttpRequest.newBuilder` for it** (#445).
  Before that class
  five call sites assembled the URL each, which is why there was nowhere authentication could be
  added once. An unauthenticated request is SILENT — no behavioural test notices one — so the rule
  is pinned by reading the source. `RemoteLlmEngine` addresses the operator's own configured
  endpoint and this rule says nothing about it.
  → ADR Decision 103; `ArchitectureGuardTest.everyLocalServerRequestCarriesTheModulesKey`,
  `theLocalServerAddressIsSpelledInOnePlace`.
- **The secret reaches the child in its ENVIRONMENT, never on its command line.** That is
  `LlamaServerEndpoint.handOverTo`, and `LlamaServerEndpoint.API_KEY_ENV` is the one place the
  variable is spelled. An argument vector is world-readable, so a key argument would hand the
  secret to the principal the change locks out. Do not add one to
  `LocalLlmEngine.buildServerCommand`, and do not assert on one.
  → ADR Decision 103, rows 2 and 3.
- **A listener answering `/health` is not the server until readiness says so.**
  `LocalLlmEngine.requireLoopbackPortFree` runs before the child is launched and
  `requireHealthyListenerIsTheSpawnedChild` after the health reply; the three questions the second
  asks each have their own reason, given in its javadoc.
  **Only those two tie readiness to the child.**
  The key probes establish that the key is in force, and a bearer token authenticates the
  CLIENT to the server and never the server to the client, so do not write either probe up as peer
  authentication — here, in a decision, or in a comment.
  → ADR Decision 103; `LocalLlmServerAuthTest`.
- **`--host 127.0.0.1` and `--no-webui` are load-bearing, not tidiness.** The first stops an
  inherited `LLAMA_ARG_HOST` widening the bind; the second closes the one route on the port that
  answers an unauthenticated caller.
  → ADR Decision 103, rows 4 and 6; `LocalLlmServerAuthTest`.

## Its opt-in test suites

- **The suites `LlmEndpointTestSupport` serves reach a server the TESTER started.** A server this
  module spawned enforces a per-start secret that is never logged and cannot be recovered, so
  `isReachable` asks the completions route and not only `/health`, which llama-server serves
  publicly; a keyed endpoint of the tester's own is named by `chartsearchai.test.llm.apiKey`. The
  request shape and the credential live in that one class, never copied into a suite.
  → ADR Decision 103; `LlmEndpointTestSupport`.
