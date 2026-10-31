\set ON_ERROR_STOP on
-- Optional, owner-reviewed installer. Never place this file in Flyway locations.
-- Required psql variables name EXISTING roles; this script creates no roles.
BEGIN;
SELECT set_config('recovery.owner', :'recovery_owner', true),
       set_config('recovery.runtime', :'recovery_runtime', true);

DO $$
DECLARE
    runtime_role name := current_setting('recovery.runtime');
    owner_role name := current_setting('recovery.owner');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = owner_role)
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        RAISE EXCEPTION 'Both reviewed roles must already exist';
    END IF;
    IF owner_role = runtime_role OR pg_has_role(runtime_role, owner_role, 'MEMBER') THEN
        RAISE EXCEPTION 'Runtime must not own or inherit the installer owner role';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role
               AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Runtime role has administrative privileges';
    END IF;
    IF has_schema_privilege(runtime_role, 'public', 'CREATE') THEN
        RAISE EXCEPTION 'Runtime must not have CREATE on the public schema';
    END IF;
END $$;

SET LOCAL ROLE :"recovery_owner";
SET LOCAL search_path = pg_catalog, public;

CREATE TABLE public.sca_recovery_operation (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version >= 0),
    issuer TEXT NOT NULL CHECK (issuer <> ''),
    tenant TEXT NOT NULL CHECK (tenant <> ''),
    client_id TEXT NOT NULL CHECK (client_id <> ''),
    delegating_client_id TEXT,
    actor_subject TEXT NOT NULL CHECK (actor_subject <> ''),
    target_issuer TEXT NOT NULL CHECK (target_issuer <> ''),
    target_realm TEXT NOT NULL CHECK (target_realm <> ''),
    target_subject TEXT NOT NULL CHECK (target_subject <> ''),
    population TEXT NOT NULL CHECK (population <> ''),
    purpose TEXT NOT NULL CHECK (purpose <> ''),
    lock_generation TEXT NOT NULL CHECK (lock_generation <> ''),
    owner_link_id TEXT NOT NULL CHECK (owner_link_id <> ''),
    identity_link_attestation_id TEXT NOT NULL CHECK (identity_link_attestation_id <> ''),
    lock_attestation_id TEXT NOT NULL CHECK (lock_attestation_id <> ''),
    create_key TEXT NOT NULL CHECK (create_key <> ''),
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'CONSUMED', 'EXHAUSTED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0 AND attempt_count <= max_attempts),
    challenge_count INTEGER NOT NULL CHECK (challenge_count >= 0),
    max_challenges INTEGER NOT NULL CHECK (max_challenges > 0 AND challenge_count <= max_challenges),
    execution_id UUID UNIQUE,
    consume_key TEXT,
    receipt_expires_at TIMESTAMPTZ,
    execution_outcome TEXT CHECK (execution_outcome IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT recovery_create_key UNIQUE (issuer, tenant, client_id, actor_subject, create_key),
    CONSTRAINT recovery_target_generation_once UNIQUE
        (target_issuer, target_subject, tenant, population, purpose, lock_generation),
    CONSTRAINT recovery_receipt_state CHECK (
        (state = 'CONSUMED' AND execution_id IS NOT NULL AND consume_key IS NOT NULL
         AND consume_key <> '' AND receipt_expires_at IS NOT NULL AND execution_outcome IS NOT NULL)
        OR (state <> 'CONSUMED' AND execution_id IS NULL AND consume_key IS NULL
            AND receipt_expires_at IS NULL AND execution_outcome IS NULL))
);

CREATE TABLE public.sca_recovery_challenge (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version >= 0),
    operation_id UUID NOT NULL REFERENCES public.sca_recovery_operation(id),
    category TEXT NOT NULL CHECK (category IN ('POSSESSION', 'KNOWLEDGE')),
    provider_id TEXT NOT NULL CHECK (provider_id <> ''),
    enrollment_id TEXT NOT NULL CHECK (enrollment_id <> ''),
    independence_id TEXT NOT NULL CHECK (independence_id <> ''),
    provider_challenge_id TEXT NOT NULL CHECK (provider_challenge_id <> ''),
    issue_reservation_id UUID NOT NULL UNIQUE,
    verification_reservation_id UUID UNIQUE,
    verification_reserved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0 AND attempt_count <= max_attempts),
    verified BOOLEAN NOT NULL,
    attestation_id TEXT,
    proof_expires_at TIMESTAMPTZ,
    CONSTRAINT recovery_verification_reservation CHECK (
        (verification_reservation_id IS NULL AND verification_reserved_at IS NULL)
        OR (verification_reservation_id IS NOT NULL AND verification_reserved_at IS NOT NULL AND NOT verified)),
    CONSTRAINT recovery_proof_state CHECK (
        (verified AND attestation_id IS NOT NULL AND attestation_id <> '' AND proof_expires_at IS NOT NULL
         AND proof_expires_at > created_at AND proof_expires_at <= expires_at)
        OR (NOT verified AND attestation_id IS NULL AND proof_expires_at IS NULL))
);
CREATE INDEX recovery_challenge_operation ON public.sca_recovery_challenge(operation_id);
CREATE UNIQUE INDEX recovery_attestation_once
    ON public.sca_recovery_challenge(provider_id, attestation_id) WHERE attestation_id IS NOT NULL;
CREATE UNIQUE INDEX recovery_provider_challenge_once
    ON public.sca_recovery_challenge(provider_id, provider_challenge_id);

CREATE TABLE public.sca_recovery_audit (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES public.sca_recovery_operation(id),
    event_type TEXT NOT NULL CHECK (event_type <> ''),
    occurred_at TIMESTAMPTZ NOT NULL,
    execution_id UUID,
    reservation_id UUID
);
CREATE INDEX recovery_audit_operation ON public.sca_recovery_audit(operation_id);
CREATE UNIQUE INDEX recovery_audit_reservation_event_once
    ON public.sca_recovery_audit(event_type, reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE public.sca_recovery_boundary_audit (
    id UUID PRIMARY KEY,
    issuer TEXT,
    tenant TEXT,
    actor_subject TEXT,
    client_id TEXT,
    population TEXT,
    action TEXT,
    outcome TEXT NOT NULL CHECK (outcome IN ('ADMITTED', 'REFUSED', 'REJECTED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT recovery_boundary_actor CHECK (
        (issuer IS NULL AND tenant IS NULL AND actor_subject IS NULL AND client_id IS NULL AND population IS NULL)
        OR (issuer IS NOT NULL AND issuer <> '' AND tenant IS NOT NULL AND tenant <> ''
            AND actor_subject IS NOT NULL AND actor_subject <> '' AND client_id IS NOT NULL AND client_id <> ''
            AND population IS NOT NULL AND population <> ''))
);

CREATE FUNCTION public.recovery_operation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    database_now TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'OPEN' OR NEW.attempt_count <> 0 OR NEW.challenge_count <> 0 THEN
            RAISE EXCEPTION 'Recovery operations must begin open with unused budgets' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.issuer, NEW.tenant, NEW.client_id, NEW.delegating_client_id,
           NEW.actor_subject, NEW.target_issuer, NEW.target_realm, NEW.target_subject,
           NEW.population, NEW.purpose, NEW.lock_generation, NEW.owner_link_id,
           NEW.identity_link_attestation_id, NEW.lock_attestation_id, NEW.create_key,
           NEW.created_at, NEW.expires_at, NEW.max_attempts, NEW.max_challenges)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.issuer, OLD.tenant, OLD.client_id, OLD.delegating_client_id,
           OLD.actor_subject, OLD.target_issuer, OLD.target_realm, OLD.target_subject,
           OLD.population, OLD.purpose, OLD.lock_generation, OLD.owner_link_id,
           OLD.identity_link_attestation_id, OLD.lock_attestation_id, OLD.create_key,
           OLD.created_at, OLD.expires_at, OLD.max_attempts, OLD.max_challenges) THEN
        RAISE EXCEPTION 'Recovery operation binding and limits are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.attempt_count < OLD.attempt_count
       OR NEW.challenge_count < OLD.challenge_count
       OR NEW.attempt_count > OLD.attempt_count + 1 OR NEW.challenge_count > OLD.challenge_count + 1 THEN
        RAISE EXCEPTION 'Recovery operation version/counters must advance monotonically' USING ERRCODE = '23514';
    END IF;
    IF OLD.state <> 'OPEN' AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION 'Recovery operation terminal state cannot reopen' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'CONSUMED' AND
       ROW(NEW.execution_id, NEW.consume_key, NEW.receipt_expires_at)
       IS DISTINCT FROM ROW(OLD.execution_id, OLD.consume_key, OLD.receipt_expires_at) THEN
        RAISE EXCEPTION 'Recovery receipt is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.execution_outcome IN ('SUCCEEDED', 'FAILED')
       AND NEW.execution_outcome IS DISTINCT FROM OLD.execution_outcome THEN
        RAISE EXCEPTION 'Recovery execution terminal outcome cannot change' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'CONSUMED' AND OLD.state = 'OPEN' THEN
        IF database_now >= NEW.expires_at OR NEW.receipt_expires_at <= database_now
           OR NEW.receipt_expires_at > NEW.expires_at THEN
            RAISE EXCEPTION 'Recovery consumption or receipt expired' USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM public.sca_recovery_challenge possession
            JOIN public.sca_recovery_challenge knowledge ON knowledge.operation_id = possession.operation_id
            WHERE possession.operation_id = NEW.id AND possession.category = 'POSSESSION'
              AND knowledge.category = 'KNOWLEDGE' AND possession.verified AND knowledge.verified
              AND possession.expires_at > database_now AND knowledge.expires_at > database_now
              AND possession.proof_expires_at >= NEW.receipt_expires_at
              AND knowledge.proof_expires_at >= NEW.receipt_expires_at
              AND possession.independence_id <> knowledge.independence_id
              AND ROW(possession.provider_id, possession.enrollment_id)
                  IS DISTINCT FROM ROW(knowledge.provider_id, knowledge.enrollment_id)
              AND ROW(possession.provider_id, possession.attestation_id)
                  IS DISTINCT FROM ROW(knowledge.provider_id, knowledge.attestation_id)
        ) THEN
            RAISE EXCEPTION 'Recovery consumption requires current independent possession and knowledge' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recovery_operation_guard BEFORE INSERT OR UPDATE ON public.sca_recovery_operation
    FOR EACH ROW EXECUTE FUNCTION public.recovery_operation_guard();

CREATE FUNCTION public.recovery_challenge_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.verified OR NEW.attempt_count <> 0 OR NEW.verification_reservation_id IS NOT NULL THEN
            RAISE EXCEPTION 'Recovery challenges must begin unverified with unused attempts' USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.sca_recovery_audit
                       WHERE operation_id = NEW.operation_id AND event_type = 'CHALLENGE_RESERVED'
                         AND reservation_id = NEW.issue_reservation_id) THEN
            RAISE EXCEPTION 'Recovery challenge requires a durable issue reservation' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.operation_id, NEW.category, NEW.provider_id, NEW.enrollment_id,
           NEW.independence_id, NEW.provider_challenge_id, NEW.issue_reservation_id,
           NEW.created_at, NEW.expires_at, NEW.max_attempts)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.operation_id, OLD.category, OLD.provider_id, OLD.enrollment_id,
           OLD.independence_id, OLD.provider_challenge_id, OLD.issue_reservation_id,
           OLD.created_at, OLD.expires_at, OLD.max_attempts)
       OR NEW.version <> OLD.version + 1 OR NEW.attempt_count < OLD.attempt_count THEN
        RAISE EXCEPTION 'Recovery challenge binding is immutable and counters must advance' USING ERRCODE = '23514';
    END IF;
    IF OLD.verified AND ROW(NEW.verified, NEW.attestation_id, NEW.proof_expires_at)
       IS DISTINCT FROM ROW(OLD.verified, OLD.attestation_id, OLD.proof_expires_at) THEN
        RAISE EXCEPTION 'Verified recovery evidence cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.verification_reservation_id IS NULL THEN
        IF NEW.verification_reservation_id IS NOT NULL THEN
            IF NEW.attempt_count <> OLD.attempt_count + 1 OR OLD.verified
               OR clock_timestamp() >= NEW.expires_at THEN
                RAISE EXCEPTION 'Recovery verification must reserve an unexpired attempt' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.attempt_count <> OLD.attempt_count OR NEW.verified IS DISTINCT FROM OLD.verified THEN
            RAISE EXCEPTION 'Recovery proof requires a prior durable reservation' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.attempt_count <> OLD.attempt_count
       OR (NEW.verification_reservation_id IS NOT NULL
           AND ROW(NEW.verification_reservation_id, NEW.verification_reserved_at)
               IS DISTINCT FROM ROW(OLD.verification_reservation_id, OLD.verification_reserved_at)) THEN
        RAISE EXCEPTION 'An active recovery verification reservation cannot be replaced or recounted' USING ERRCODE = '23514';
    END IF;
    IF NOT OLD.verified AND NEW.verified
       AND (clock_timestamp() >= NEW.expires_at OR clock_timestamp() >= NEW.proof_expires_at) THEN
        RAISE EXCEPTION 'Recovery evidence expired before verification was saved' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recovery_challenge_guard BEFORE INSERT OR UPDATE ON public.sca_recovery_challenge
    FOR EACH ROW EXECUTE FUNCTION public.recovery_challenge_guard();

CREATE FUNCTION public.recovery_audit_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Recovery audit is append-only' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER recovery_audit_append_only BEFORE UPDATE OR DELETE ON public.sca_recovery_audit
    FOR EACH ROW EXECUTE FUNCTION public.recovery_audit_append_only();
CREATE TRIGGER recovery_audit_no_truncate BEFORE TRUNCATE ON public.sca_recovery_audit
    FOR EACH STATEMENT EXECUTE FUNCTION public.recovery_audit_append_only();
CREATE TRIGGER recovery_boundary_audit_append_only BEFORE UPDATE OR DELETE ON public.sca_recovery_boundary_audit
    FOR EACH ROW EXECUTE FUNCTION public.recovery_audit_append_only();
CREATE TRIGGER recovery_boundary_audit_no_truncate BEFORE TRUNCATE ON public.sca_recovery_boundary_audit
    FOR EACH STATEMENT EXECUTE FUNCTION public.recovery_audit_append_only();

CREATE FUNCTION public.recovery_consumption_audit_required() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempt_count > (SELECT count(*) FROM public.sca_recovery_audit
                           WHERE operation_id = NEW.id AND event_type = 'PROOF_RESERVED' AND reservation_id IS NOT NULL)
       OR NEW.challenge_count > (SELECT count(*) FROM public.sca_recovery_audit
                                WHERE operation_id = NEW.id AND event_type = 'CHALLENGE_RESERVED' AND reservation_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Recovery budgets require durable reservation audit events' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'CONSUMED' AND NOT EXISTS (
        SELECT 1 FROM public.sca_recovery_audit
        WHERE operation_id = NEW.id AND event_type = 'CONSUMED' AND execution_id = NEW.execution_id
    ) THEN
        RAISE EXCEPTION 'Recovery consumption requires its execution-bound audit event' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER recovery_consumption_audit_required
    AFTER INSERT OR UPDATE ON public.sca_recovery_operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.recovery_consumption_audit_required();

CREATE FUNCTION public.recovery_challenge_reservation_audit_required() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.verification_reservation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.sca_recovery_audit WHERE operation_id = NEW.operation_id
          AND event_type = 'PROOF_RESERVED' AND reservation_id = NEW.verification_reservation_id
    ) THEN
        RAISE EXCEPTION 'Recovery verification requires its durable reservation audit' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.verification_reservation_id IS NOT NULL
       AND NEW.verification_reservation_id IS NULL AND NOT EXISTS (
        SELECT 1 FROM public.sca_recovery_audit WHERE operation_id = NEW.operation_id
          AND reservation_id = OLD.verification_reservation_id
          AND event_type = CASE WHEN NEW.verified THEN 'PROOF_ACCEPTED' ELSE 'PROOF_REJECTED' END
    ) THEN
        RAISE EXCEPTION 'Recovery proof completion requires its reservation-bound result audit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER recovery_challenge_reservation_audit_required
    AFTER INSERT OR UPDATE ON public.sca_recovery_challenge
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION public.recovery_challenge_reservation_audit_required();

REVOKE ALL ON public.sca_recovery_operation, public.sca_recovery_challenge, public.sca_recovery_audit FROM PUBLIC;
REVOKE ALL ON public.sca_recovery_operation, public.sca_recovery_challenge, public.sca_recovery_audit FROM :"recovery_runtime";
REVOKE ALL ON public.sca_recovery_boundary_audit FROM PUBLIC, :"recovery_runtime";
GRANT SELECT, INSERT, UPDATE ON public.sca_recovery_operation, public.sca_recovery_challenge TO :"recovery_runtime";
GRANT SELECT, INSERT ON public.sca_recovery_audit TO :"recovery_runtime";
GRANT SELECT, INSERT ON public.sca_recovery_boundary_audit TO :"recovery_runtime";
REVOKE ALL ON FUNCTION public.recovery_operation_guard(), public.recovery_challenge_guard(),
    public.recovery_audit_append_only(), public.recovery_consumption_audit_required(),
    public.recovery_challenge_reservation_audit_required() FROM PUBLIC;

DO $$
DECLARE
    runtime_role name := current_setting('recovery.runtime');
BEGIN
    IF has_table_privilege(runtime_role, 'public.sca_recovery_audit', 'UPDATE')
       OR has_table_privilege(runtime_role, 'public.sca_recovery_boundary_audit', 'UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES')
       OR has_table_privilege(runtime_role, 'public.sca_recovery_audit', 'DELETE,TRUNCATE,TRIGGER,REFERENCES')
       OR has_table_privilege(runtime_role, 'public.sca_recovery_operation', 'DELETE,TRUNCATE,TRIGGER,REFERENCES')
       OR has_table_privilege(runtime_role, 'public.sca_recovery_challenge', 'DELETE,TRUNCATE,TRIGGER,REFERENCES') THEN
        RAISE EXCEPTION 'Runtime inherits forbidden recovery privileges; review role/default grants';
    END IF;
END $$;
COMMIT;
