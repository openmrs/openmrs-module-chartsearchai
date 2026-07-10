# Contributor Rules

- Fix production behavior when a test exposes a defect. Do not weaken an assertion merely to make a broken path green.
- Add or update tests when intended behavior changes. Tests must exercise the public production boundary whenever practical.
- Prefer root-cause fixes and small, reviewable changes. Review the complete diff before committing.
- Keep ChartSearchAI a thin OpenMRS integration: patient authorization, session lifecycle, audit, persistence, feedback, and SSE relay.
- Send one request per turn to the configured med-agent-hub product profile. Do not decompose answer, review, grounding, or In-Depth stages in Java.
- Do not add model serving, process management, prompt orchestration, temporal or drug validation, citation grounding, or context retrieval to this module.
- Do not add a compile-time or runtime dependency on Querystore. Querystore is one optional context source configured behind med-agent-hub.
- Profile discovery must relay med-agent-hub metadata. Do not curate provider endpoints or model lists in Java.
- Preserve hub lifecycle and evidence metadata through persistence, reload, and SSE events.
- Keep current documentation aligned with the hub-relay architecture. Mark historical migration records explicitly instead of presenting their old behavior as current.

## Verification

Run the full Maven suite with a writable OpenMRS application-data directory:

```bash
mvn -DOPENMRS_APPLICATION_DATA_DIRECTORY=/tmp/chartsearchai-openmrs-appdata test
```

The parent validation harness also enforces the cross-repository consolidation and documentation-drift gates.
