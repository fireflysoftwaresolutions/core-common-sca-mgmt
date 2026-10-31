#!/usr/bin/env python3
"""Read-only source checks; never opens a database or executes the installer."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
models = root / "core-common-sca-mgmt-models"
migrations = models / "src/main/resources/db/migration"
assert {path.name for path in migrations.glob("*.sql")} == {
    "V1__Create_Enums.sql", "V2__Create_Tables.sql", "V3__Create_casts.sql", "V4__Add_Enums.sql"
}, "Default Flyway discovery must retain only V1-V4"
installer = (Path(__file__).parent / "install.sql").read_text()
assert not re.search(r"\bCREATE\s+ROLE\b", installer, re.IGNORECASE), "Installer must not create roles"
assert "SET LOCAL ROLE :\"recovery_owner\"" in installer
assert "GRANT SELECT, INSERT ON public.sca_recovery_audit" in installer
assert "BEFORE UPDATE OR DELETE ON public.sca_recovery_audit" in installer
assert "BEFORE TRUNCATE ON public.sca_recovery_audit" in installer
assert "DEFERRABLE INITIALLY DEFERRED" in installer
assert "clock_timestamp()" in installer
assert "(target_issuer, target_subject, tenant, population, purpose, lock_generation)" in installer
assert "issue_reservation_id UUID NOT NULL UNIQUE" in installer
assert "verification_reservation_id UUID UNIQUE" in installer
assert "reservation_id UUID" in installer
assert "event_type = 'CHALLENGE_RESERVED'" in installer
assert "event_type = 'PROOF_RESERVED'" in installer
for entity in ("Operation", "Challenge", "Audit"):
    source = (models / "src/main/java/io/fireflysoftware/core/sca/models/entities"
              / f"SCARecovery{entity}.java").read_text()
    table = re.search(r'@Table\("([^\"]+)"\)', source).group(1)
    definition = re.search(rf"CREATE TABLE public\.{table} \((.*?)\n\);", installer, re.DOTALL).group(1)
    for column in re.findall(r'@Column\("([^\"]+)"\)', source):
        assert re.search(rf"^\s+{column}\s+", definition, re.MULTILINE), f"Missing mapped column: {table}.{column}"
assert not any("sca_recovery" in path.read_text() for path in migrations.glob("*.sql")), \
    "Recovery installation must not enter the existing runtime migration location"
print("Recovery installer source checks passed; SQL and Java were not executed.")
