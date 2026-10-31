# Optional recovery schema installation

This is an offline, operator-reviewed installation candidate. It is outside every application resource directory and Flyway location. Ordinary startup still discovers only the original V1–V4 migrations. Disabling recovery routes does not authorize a schema or privilege change. No installer, grant, database test, native identity action, or production operation was executed while preparing this source.

The owner must review the schema, selected database, role graph, default privileges, retention policy, and receipt/independent-factor policy before installation. Keep recovery disabled until its receiving authentication/policy boundary, providers, native generation-bound action, and reconciliation are separately qualified. This SQL does not provision any of those capabilities.

## Roles and database

Use an approved PostgreSQL database with the original SCA schema already installed. The mapped recovery entities use the `public` schema. Supply two existing roles:

- `recovery_owner` owns the new tables, indexes, functions, and triggers. It must be allowed to create objects in `public`; the operator must be allowed to `SET ROLE` to it. Prefer a separately administered role without application login.
- `recovery_runtime` is the actual application's login role. It must not own or inherit the owner role, have administrative privileges, or have `CREATE` on `public`. Inherited/default grants must not supply forbidden recovery privileges. Resolve a failed precondition through a separately reviewed role change; this installer never creates roles, changes membership, or repairs shared-schema privileges.

Review all runtime memberships, including roles it can assume. The installer grants only `SELECT, INSERT, UPDATE` on recovery operation/challenge tables and `SELECT, INSERT` on recovery audit. It rejects inherited `DELETE, TRUNCATE, TRIGGER, REFERENCES`, and rejects audit `UPDATE`. Runtime cannot alter/disable owner-controlled triggers. Audit triggers additionally reject row mutation and truncation. An administrative owner can change the schema; operational access control and audit remain required.

The tables are separate from legacy SCA CRUD and have no legacy-operation foreign keys or cascade paths. No legacy record is converted into recovery proof. Recovery records must only be exposed through the guarded recovery service, never through general SCA CRUD.

## Reviewed installation procedure

First inspect `install.sql` and `verify.sql`. Record the reviewed source revision/checksum and approved database/role identities in the operator's change record. Use the approved connection/service configuration; keep passwords in the normal secret mechanism rather than command arguments or source files.

Only after approval, an operator may run the following, substituting the reviewed service and existing role names:

```sh
psql service=reviewed_recovery_install -X --set=ON_ERROR_STOP=1 \
  --set=recovery_owner=reviewed_owner --set=recovery_runtime=reviewed_runtime \
  --file=ops/recovery-installer/install.sql
```

Installation is one transaction. Existing recovery tables/functions cause failure instead of silently adopting or overwriting unknown objects. An installation error rolls back the DDL and grants. Do not rerun by dropping retained recovery data. There is intentionally no destructive uninstall script.

Then inspect the read-only catalog report:

```sh
psql service=reviewed_recovery_inspection -X --set=ON_ERROR_STOP=1 \
  --set=recovery_owner=reviewed_owner --set=recovery_runtime=reviewed_runtime \
  --file=ops/recovery-installer/verify.sql
```

Confirm exactly three tables owned by the reviewed owner, the minimal privilege matrix above, enabled operation/challenge/audit/consumption/reservation-audit triggers, and the expected uniqueness indexes. `verify.sql` reports catalog state; it does not change or repair it. Runtime deployments must retain a trusted `public` search path and the restricted role; do not run the application with the installer credentials.

## Database guarantees and limits

Operations begin `OPEN` with unused budgets. Operation/challenge versions and counters are monotonic; immutable identity, issuer, exact tenant, client/delegation, purpose, generation, enrollment, expiry, and limits cannot be rewritten. An accepted challenge cannot be changed back into reusable evidence. Provider ceremony IDs and provider attestation IDs are unique across recovery operations.

One operation is allowed for each `(target_issuer, target_subject, tenant, population, purpose, lock_generation)` tuple. Expiry, exhaustion, a different actor/client, or a fresh creation key cannot reset that generation's budgets. A newly attested native lock generation requires its own independently admitted operation; this does not grant authority to manufacture or advance a generation.

Before calling a challenge or proof provider, the service commits the relevant budget increment and a UUID-bound `CHALLENGE_RESERVED` or `PROOF_RESERVED` audit event. A challenge result must match a durable issue reservation and can be inserted only once. Verification reserves both operation and challenge attempts, and permits only one active verification reservation per challenge. Completion clears that exact reservation without recounting, with a reservation-bound accepted/rejected audit. Deferred constraints require reservation events behind spent counters and proof completion. A provider failure or process crash keeps the already spent budget and active proof reservation; a retry cannot invoke the provider again under a replacement reservation. Unknown results require approved reconciliation, without counter resets or proof replay.

Consumption requires a successful optimistic version update, database wall time before operation expiry, an unexpired receipt, and currently valid independent possession and knowledge evidence. A deferred constraint requires a matching `CONSUMED` audit event for that execution ID before commit. Transaction failure, including failed audit insertion, leaves the operation unconsumed. Consumed/exhausted states cannot reopen, the receipt cannot be replaced, and terminal execution outcomes cannot change.

The database cannot query Keycloak's current lock generation, authenticate a human, verify an external provider, or perform unlock. The guarded service must revalidate current actor/policy, exact target, and authoritative native generation before consumption/execution. Repository-level proof is not native provider or end-to-end acceptance.

## Qualification source

`SCARecoveryRepositoryIT` uses the actual Spring Data mapped repositories and nonpooled connections against an explicitly installed, disposable PostgreSQL fixture. It is selected through the explicit `recovery-postgres-it` Maven profile; its name alone does not execute it. The fixture must use the restricted runtime role and must never be a retained/customer database. Tests append uniquely identified records and do not erase them; fixture disposal belongs to the CI database lifecycle.

After provisioning the approved fixture and setting the variables below, the qualified CI environment runs:

```sh
mvn -B -Precovery-postgres-it -pl core-common-sca-mgmt-models -am verify
```

The explicitly selected suite fails if any required variable is missing:

| Variable | Fixture value |
| --- | --- |
| `SCA_RECOVERY_TEST_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/disposable_recovery` |
| `SCA_RECOVERY_TEST_JDBC_URL` | `jdbc:postgresql://localhost:5432/disposable_recovery` |
| `SCA_RECOVERY_TEST_USER` | Restricted runtime fixture role |
| `SCA_RECOVERY_TEST_PASSWORD` | Fixture-only secret |

The managed PostgreSQL JDBC/R2DBC drivers are already present; no container library or service-local dependency version is introduced. The tests cover separate-connection budget-reservation races and competing consumption, provider-failure reservations, expiry retaining spent budgets, invalid independent evidence, global attestation replay, immutable generation binding, fresh-key budget-reset denial, consumed-receipt replay, audit-failure rollback, deferred missing-audit rejection, and forbidden runtime audit mutation. Immutable generation binding does not qualify a native generation-change race; service/provider acceptance remains separate.

The lightweight source check opens no database and runs no SQL or Java:

```sh
python3 ops/recovery-installer/check_source.py
```

Passing this source check is not SQL syntax, Java compilation, database concurrency, hosted CI, publication, or provider evidence. Run the approved Java 25 reactor and explicitly selected database suite in the qualified CI fixture before claiming those results.
