# Rules for `api/impl` — talking to the local LLM subprocess

The root `CLAUDE.md` names this file and its rules bind this directory. Read it too; nothing here
replaces it. **This file is directives and pointers** — the evidence is `docs/adr.md` and javadoc,
which is the root file's "Documenting a decision" rule.

## The spawned llama-server

- **Every request the engine sends to the spawned llama-server is built by `LlamaServerEndpoint.request`**,
  which attaches the secret minted for that server start. Take the URL from the same object —
  `completionsUrl`, `healthUrl`, `propsUrl`, `slotUrl` — and the address itself from
  `LlamaServerEndpoint.LOOPBACK_HOST` or `authority`, including in an operator-facing message and
  in the `--host` argument.
  **Nothing else may spell the loopback address or call `HttpRequest.newBuilder` for it** (#445).
  An unauthenticated request is SILENT, which is why the rule is pinned by reading the source —
  and note the guard's pattern reaches the URL form only, so a bare `"127.0.0.1"` elsewhere is
  invisible to it and this directive is what covers that. `RemoteLlmEngine` addresses the
  operator's own configured endpoint and this rule says nothing about it.
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
  `requireListenerMayBeServed` after the health reply; the three questions the second
  asks each have their own reason, given in its javadoc.
  **Only those two tie readiness to the child.**
  The key probes establish less than they look like they do, and `LlamaServerEndpoint`'s class
  javadoc says exactly what. Do not write either up as more than that — here, in a decision, or in
  a comment.
  → ADR Decision 103; `LocalLlmServerAuthTest`.
- **Nothing this module sends to its own subprocess may be proxy-routable**, unconditionally: the
  port probe takes `Proxy.NO_PROXY` and `LocalLlmEngine.getHttpClient` takes
  `HttpClient.Builder.NO_PROXY`. `RemoteLlmEngine` must stay proxy-aware, its endpoint being meant
  to leave the host. **Build no second client for it** — a guard reads client construction, but
  none can read the proxy setting of one that is built.
  → ADR Decision 103, row 12b; `LocalLlmServerAuthTest.theClientTalkingToTheLocalServerUsesNoProxy`,
  `theProbeIsNotRoutedThroughAConfiguredProxy`.
- **`--host 127.0.0.1` and `--no-webui` are load-bearing, not tidiness.** The first stops an
  inherited `LLAMA_ARG_HOST` widening the bind; the second closes the Web UI root, which is not the
  only unauthenticated route on the port but is the only one this module can close and does not use.
  → ADR Decision 103, rows 4 and 6; `LocalLlmServerAuthTest`.

## Its opt-in test suites

- **The suites `LlmEndpointTestSupport` serves reach a server the TESTER started.** A server this
  module spawned enforces a per-start secret the module never logs or writes down, so
  `isReachable` asks the completions route and not only `/health`, which llama-server serves
  publicly; a keyed endpoint of the tester's own is named by `chartsearchai.test.llm.apiKey`. The
  request shape and the credential live in that one class, never copied into a suite.
  → ADR Decision 103; `LlmEndpointTestSupport`.
