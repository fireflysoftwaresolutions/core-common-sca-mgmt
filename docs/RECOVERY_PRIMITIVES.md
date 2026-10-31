# Guarded recovery primitives — source candidate

This additive candidate prepares the Core SCA owner for permanent-account recovery. It does **not** unlock a native identity, verify a password, supply an enrolled factor, establish a restricted recovery principal or create financial authority. Recovery is disabled by default. Ordinary legacy SCA CRUD cannot authorize recovery or access its separate records; the candidate receiving security chain also denies legacy runtime routes until an independently approved contract exists. Existing Java interfaces and generated SDK/specification files are not rewritten.

## Ownership and admission

New operations live at `/api/v1/sca/recovery-operations`, with original-producer operation IDs `createRecoveryOperation`, `issueRecoveryChallenge`, `verifyRecoveryChallenge`, `consumeRecoveryAuthorization` and `recordRecoveryExecution`. They require both the bearer/current-policy receiver gate and the Core's independent qualified recovery admission. Body binding fields are comparison expectations, not identity or ownership evidence. Issuer, opaque tenant, subject, client and population must match the admitted actor; machine credentials and invented delegation headers cannot substitute for human recovery proof.

The Core admission capability must independently establish the current population-specific identity link, exact target lock generation, purpose and supported action through an approved restricted recovery ceremony. Missing or unqualified capabilities prevent enablement. The default implementations refuse; there is no permissive sandbox/native substitute.

The enabled receiver requires explicit HTTPS issuer/JWKS, intended audience, exact client/population allowlists, required opaque `tenant_id`, asymmetric JWT validation and current strict PDP permission. Unsupported obligations, expired identity/proof, missing context or policy failures deny. Failed attempts and acknowledgements must not disclose which factor was wrong, existence, remaining attempts or native account status. These source checks do not establish timing-side-channel resistance.

## Protected storage and budgets

Mapped Spring Data repositories own operation, challenge and insert-only audit records. Every mutation and its audit use the same owning R2DBC transaction. Optimistic versions and database constraints arbitrate independent connections; JVM locks or cache flags do not authorize recovery. Database wall time governs expiry. The separately reviewed [offline installer](../ops/recovery-installer/README.md) is outside application resources and automatic Flyway discovery. V1–V4 and old records remain unchanged. It requires separate existing owner/runtime roles and grants runtime no audit update/delete/truncate rights; no installer or database grant is implied by this source candidate.

One target/lock-generation operation retains its original budgets even after expiry or exhaustion. Changing the create key, actor or client cannot reset them. A later native generation is a separate authoritative identity state, not a client-selected way to regain attempts. Recovery budgets are bounded configuration values and are separate from Firefly Identity's permanent three-authentication-failure policy.

Challenge delivery and verification reserve their budgets and append immutable reservation audit **before** invoking a provider. Concurrent callers cannot obtain more external verifier calls by losing an optimistic race afterward. Cancellation, provider failure and crashes retain spent reservations. Completion must bind the exact reservation, refresh admission, recheck current bindings/expiry and commit result plus audit without charging twice. Challenge reissue cannot reset operation-wide attempts. Provider attestations/enrollments and independent factor categories are authoritative provider evidence, never caller-supplied success flags or two labels on one code.

Raw knowledge credentials stay in their identity boundary. Core accepts only the selected possession response or a qualified provider attestation; it does not store plaintext reusable codes or submitted responses. Secret-bearing records override `toString` with redacted output. External errors do not preserve secret-bearing messages/causes. Public verification returns only a generic acknowledgement. Acknowledging an admitted denial requires a committed durable rejection-audit receipt; logging alone is not that capability. Receiving-boundary authentication/malformed-request/policy denials have their own mandatory qualified audit gate.

## Consumption and execution

Consumption atomically checks the same immutable binding/generation, unexpired independent knowledge and possession evidence, budgets and unconsumed state, then records one execution identity and audit. Exact consume retries retain that execution identity; another key cannot manufacture another authorization. The receipt is an opaque online-verifiable reference, **not an executed native unlock**. A qualified executor still needs an approved receipt-verification mechanism, authoritative generation-bound idempotent transition and current authorization before acting. None is supplied by generic user `enabled=true`, existing SCA `VERIFIED` status or this distribution.

Execution reporting verifies an authoritative executor journal and preserves monotonic outcomes. A timeout remains unknown and requires reconciliation under the same execution ID; consumed proof must not be reopened or converted into a new unlock. Native success, session revocation/current receiver blocking, customer/workforce factor enrollment, assisted identity reproofing and production/regulatory acceptance remain separate gates.

## Qualification

The maintained source includes Core policy tests, receiver/controller/original-producer contract tests and an explicitly selected `SCARecoveryRepositoryIT` PostgreSQL suite. Mock tests do not establish database concurrency or provider behavior. The real database suite must run, with no skips, through `-Precovery-postgres-it`; the qualification summary rejects a successful Maven result if that report is missing or skipped.

Hosted qualification uses only a newly created labelled disposable PostgreSQL container, random fixture secrets, original V1–V4 and the optional installer under separately restricted roles. It never selects a supplied existing database. The helper refuses local/non-hosted or remote-Docker execution and stops only its labelled container. Raw database/Maven logs, credentials and state are not uploaded.

The fixture overlays only the owning parent `relativePath` to exact corrected source parent `6442bd51733ade4d5f4583e1afe812dd2c520b40`, selecting the genuine Jafly parent/BOM train 26.10.23. This is source qualification, **not adoption of a newly published parent 26.10.6**, a service/SDK release or native recovery acceptance. No released artifact is overwritten. Canonical SDK/specification changes must come from the original producer and its real generation/publication lifecycle, with independent consumer acceptance.

Compilation, tests, SQL installation, concurrency, original-producer and hosted outcomes are pending until an exact run and source identity are recorded. No local Java/Maven/database/container/dependency execution was performed for this candidate.
