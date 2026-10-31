"""Owned hosted-only disposable database; never connect to an existing service."""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.qualification'
IMAGE = 'postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5'
LABEL = 'org.firefly.sca.recovery.fixture'


def command(arguments, **kwargs):
    return subprocess.run(arguments, check=True, capture_output=True, **kwargs)


def require_hosted():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise RuntimeError('This fixture is restricted to its hosted ephemeral runner')
    endpoint = command(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'], text=True).stdout
    if not json.loads(endpoint).startswith('unix://'):
        raise RuntimeError('Only the local hosted Docker daemon is admitted')


def psql(container, content, extra=()):
    result = subprocess.run(['docker', 'exec', '-i', container, 'psql', '-X', '-U', 'postgres', '-d', 'sca_recovery',
                             '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', *extra], input=content,
                            capture_output=True)
    with (STATE / 'fixture-private.log').open('ab') as log:
        log.write(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('Owned fixture SQL failed; raw diagnostic remains private')


def prepare():
    os.umask(0o077)
    STATE.mkdir(exist_ok=True)
    marker = STATE / 'postgres-ownership.json'
    if marker.exists():
        raise RuntimeError('Refusing to overwrite fixture ownership')
    run = os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
    if not re.fullmatch(r'\d+-\d+', run):
        raise RuntimeError('Unexpected hosted run identity')
    container = 'sca-recovery-' + run
    password = secrets.token_hex(32)
    runtime_password = secrets.token_hex(32)
    environment = STATE / 'postgres.env'
    environment.write_text(f'POSTGRES_PASSWORD={password}\nPOSTGRES_DB=sca_recovery\n')
    marker.write_text(json.dumps({'container': container, 'run': run, 'image': IMAGE}) + '\n')
    command(['docker', 'run', '--detach', '--rm', '--name', container, '--label', f'{LABEL}={run}',
             '--publish', '127.0.0.1:5432:5432', '--env-file', str(environment), IMAGE])
    for _ in range(60):
        ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'postgres', '-d', 'sca_recovery'],
                               capture_output=True)
        if ready.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('Owned PostgreSQL fixture did not become ready')
    roles = f"""CREATE ROLE recovery_owner NOLOGIN;
CREATE ROLE recovery_runtime LOGIN PASSWORD '{runtime_password}';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO recovery_owner;
"""
    psql(container, roles.encode())
    for migration in sorted((ROOT / 'core-common-sca-mgmt-models/src/main/resources/db/migration').glob('V[1-4]__*.sql')):
        psql(container, migration.read_bytes())
    psql(container, (ROOT / 'ops/recovery-installer/install.sql').read_bytes(),
         ('-v', 'recovery_owner=recovery_owner', '-v', 'recovery_runtime=recovery_runtime'))
    print('::add-mask::' + runtime_password)
    with Path(os.environ['GITHUB_ENV']).open('a') as output:
        output.write('SCA_RECOVERY_TEST_R2DBC_URL=r2dbc:postgresql://localhost:5432/sca_recovery\n')
        output.write('SCA_RECOVERY_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/sca_recovery\n')
        output.write('SCA_RECOVERY_TEST_USER=recovery_runtime\n')
        output.write('SCA_RECOVERY_TEST_PASSWORD=' + runtime_password + '\n')
    print('Owned empty hosted fixture: original V1–V4 plus optional recovery installer; restricted runtime role ready.')


def stop():
    marker = STATE / 'postgres-ownership.json'
    if not marker.exists():
        return
    ownership = json.loads(marker.read_text())
    inspection = command(['docker', 'inspect', ownership['container']], text=True).stdout
    actual = json.loads(inspection)[0]
    if actual['Config']['Labels'].get(LABEL) != ownership['run'] or actual['Config']['Image'] != IMAGE:
        raise RuntimeError('Fixture ownership mismatch; refusing to stop it')
    command(['docker', 'stop', ownership['container']])
    print('Stopped only the labelled disposable hosted fixture; no external service was contacted.')


if __name__ == '__main__':
    try:
        require_hosted()
        if sys.argv[1:] == ['stop']:
            stop()
        elif not sys.argv[1:]:
            prepare()
        else:
            raise RuntimeError('Unsupported fixture operation')
    except Exception:
        print('Hosted recovery fixture operation failed; no raw error, credential or database log is emitted.', file=sys.stderr)
        raise SystemExit(1)
