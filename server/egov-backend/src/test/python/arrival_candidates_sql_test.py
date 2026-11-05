"""Explicit PostgreSQL test; never discovered or run by the normal Maven suite.

Runs production SQL read from TagoArrivalEtaService.java, not a copied query.
The only connections are postgres (database administration) and a newly created
smartbis_sqltest_TIMESTAMP database. Credentials are never written or printed.
"""
from pathlib import Path
import collections
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys

def main():
    ROOT = Path(__file__).resolve().parents[5]
    OUT = ROOT / '.tmp'
    TIMER = 'C:/Program Files/Git/usr/bin/timeout.exe'
    CONTAINER = 'smartbis_jangsung-postgres-db-1'
    DB = 'smartbis_sqltest_' + datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime('%Y%m%d%H%M%S')
    assert re.fullmatch(r'smartbis_sqltest_\d{14}', DB)
    assert not DB.startswith('smartbis_db') and not DB.startswith('smartbis_verify_')

    settings = {}
    config = OUT / 'copy-measure.env'
    if config.exists():
        for line in config.read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                settings[key.strip()] = value.strip().strip('\"\'')
    user = os.environ.get('SPRING_DATASOURCE_USERNAME', settings.get('SPRING_DATASOURCE_USERNAME', ''))
    if not user:
        raise SystemExit('EXISTING_TEST_USERNAME_SETTING_MISSING')
    secrets = [v for k, v in settings.items() if v and k.startswith(('SPRING_DATASOURCE_', 'TAGO_SERVICE_KEY'))]

    def psql(database, sql):
        if database not in ('postgres', DB) or database.startswith(('smartbis_db', 'smartbis_verify_')):
            raise RuntimeError('DATABASE_NAME_REJECTED')
        command = [TIMER, '60', 'podman', 'exec', '-i', '-e',
                   'PGOPTIONS=-c statement_timeout=15000 -c lock_timeout=5000', CONTAINER,
                   'psql', '-X', '-w', '-U', user, '-d', database, '-Atq', '-v', 'ON_ERROR_STOP=1']
        result = subprocess.run(command, input=sql, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=65)
        if result.returncode:
            error = result.stderr
            for secret in sorted(secrets + [user], key=len, reverse=True):
                if secret:
                    error = error.replace(secret, '***REDACTED***')
            raise RuntimeError('PSQL_EXIT_' + str(result.returncode) + ': ' + error[-2000:])
        return result.stdout.strip()

    source = ROOT / 'server/egov-backend/src/main/java/com/smartbis/backend/tago/TagoArrivalEtaService.java'
    text = source.read_text(encoding='utf-8-sig')
    blocks = re.findall(r'"""(.*?)"""', text, flags=re.S)
    snapshot = [b for b in blocks if 'CROSS JOIN bounds b' in b and 'LIMIT ?' in b]
    fallback = [b for b in blocks if 'FROM bus_positions p' in b and 'LIMIT ?' in b]
    assert len(snapshot) == len(fallback) == 1, 'PRODUCTION_QUERY_EXTRACTION_FAILED'
    approach = int(re.search(r'APPROACH_WINDOW_STOPS\s*=\s*(\d+)', text).group(1))
    limit = approach + 1
    assert limit == 3 and approach == 2
    assert all('mod(' not in q for q in snapshot + fallback)

    def bind(query, parameters):
        parts = query.split('?')
        assert len(parts) == len(parameters) + 1, 'PRODUCTION_BIND_COUNT_CHANGED'
        literals = [str(v) if isinstance(v, int) else "'" + v.replace("'", "''") + "'" for v in parameters]
        return ''.join(p + v for p, v in zip(parts, literals)) + parts[-1]

    schema = ROOT / 'server/egov-backend/local-collection/schema.sql'
    schema_before = hashlib.sha256(schema.read_bytes()).hexdigest()
    schema_text = schema.read_text(encoding='utf-8-sig')
    guard = "current_database()<>'smartbis_db'"
    assert schema_text.count(guard) == 1, 'SOURCE_SCHEMA_GUARD_CHANGED'
    memory_schema = schema_text.replace(guard, "current_database()<>'" + DB + "'", 1)
    assert memory_schema.replace("current_database()<>'" + DB + "'", guard, 1) == schema_text
    cases = [
        ('a_complete', list(range(56)), 0, [0,1,2], 0, True, []),
        ('b_missing_one_three', [n for n in range(56) if n not in (1,3)], 1, [2], 2, False, []),
        ('c_jump', list(range(56)), 3, [3,4,5], 4, False, []),
        ('d_minimum_one', list(range(1,56)), 1, [1,2,3], 2, False, []),
        ('e_only_one_even', [1,2,3], 1, [1,2,3], 2, False, []),
        ('f_first_flag_mock_false', list(range(56)), 0, [0,1,2], 0, False, []),
        ('g_overdue_odd', list(range(56)), 1, [1,2,3], 1, False, [1]),
    ]
    result = dict(database=DB, created=False, deleted=False, connected_database_names=[],
                  schema_path=str(schema.relative_to(ROOT)), schema_original_unchanged=False,
                  tests=[], failures=[], source_sql_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
    exit_code = 1
    try:
        result['connected_database_names'].append('postgres')
        existing = psql('postgres', "SELECT count(*) FROM pg_database WHERE datname='" + DB + "';")
        assert existing == '0', 'TEMP_DATABASE_ALREADY_EXISTS'
        psql('postgres', 'CREATE DATABASE "' + DB + '";')
        result['created'] = True
        actual = psql(DB, 'SELECT current_database();')
        assert actual == DB, 'CONNECTED_DATABASE_MISMATCH'
        result['connected_database_names'].append(actual)
        for version in range(1, 20):
            files = list((ROOT / 'server/database/migrations').glob(f'V{version:03d}__*.sql'))
            assert len(files) == 1, 'MIGRATION_SET_INVALID'
            sql = files[0].read_text(encoding='utf-8-sig')
            assert not re.search(r'\\connect|dblink|CREATE\s+DATABASE|COPY\s+.*PROGRAM', sql, re.I)
            psql(DB, sql)
        psql(DB, memory_schema)
        psql(DB, (ROOT / 'server/egov-backend/local-collection/run-schema.sql').read_text(encoding='utf-8-sig'))
        java_fixtures=[]
        for name, orders, current, expected, expected_target, first_trip, overdue_orders in cases:
            route = name
            fixture = "INSERT INTO bus_routes(route_id,route_name,city_code) VALUES ('" + route + "','fixture','fixture');\n"
            for order in orders:
                node = route + '_S' + str(order)
                fixture += f"INSERT INTO bus_stops(node_id,node_name,city_code) VALUES ('{node}','fixture','fixture');\n"
                fixture += f"INSERT INTO bus_route_stops(route_id,node_id,node_order,direction) VALUES ('{route}','{node}',{order},'GLOBAL');\n"
            fixture += f"INSERT INTO bus_positions(vehicle_number,route_id,node_order,observed_at,source) VALUES ('{route}_V','{route}',{current},now(),'TAGO');"
            psql(DB, fixture)
            for branch, query, args in [('snapshot', snapshot[0], [route,route,current,current,approach,limit]),
                                         ('fallback', fallback[0], [approach,limit,'fixture'])]:
                raw = psql(DB, bind(query, args))
                data = [line.split('|') for line in raw.splitlines() if line]
                if branch == 'snapshot':
                    chosen = [int(row[1]) for row in data]
                    returned=[dict(stop=row[0],order=int(row[1])) for row in data]
                else:
                    chosen = [int(row[4]) for row in data if row[0] == route]
                    returned=[dict(stop=row[1],order=int(row[4])) for row in data if row[0]==route]
                assert chosen == expected, name + '_' + branch + '_CANDIDATES_MISMATCH'
                java_fixtures.append(dict(name=name+'_'+branch,rows=returned,current=current,minimum=min(orders),expected=expected_target,first_trip=first_trip,overdue_orders=overdue_orders))
                result['tests'].append(dict(name=name+'_'+branch,candidates=chosen,expected_target=expected_target,passed=True))
        fixture_path=OUT/'odd-allowed-postgres-fixtures.json'
        fixture_path.write_text(json.dumps(java_fixtures,indent=2),encoding='utf-8')
        java_env=os.environ.copy()
        java_env['SMARTBIS_SQL_FIXTURE']=str(fixture_path)
        command=[TIMER,'900','mvn.cmd','-q','-DforkCount=0','-Dtest=ArrivalCandidateSqlFixtureTest','-DexcludedGroups=source-material','test']
        with (OUT/'odd-allowed-sql-java.log').open('w',encoding='utf-8') as log:
            java=subprocess.run(command,cwd=ROOT/'server/egov-backend',env=java_env,stdout=log,stderr=subprocess.STDOUT,timeout=910)
        result['java_selector_exit']=java.returncode
        assert java.returncode==0, 'POSTGRES_ROWS_JAVA_SELECTOR_FAILED'
        import xml.etree.ElementTree as ET
        suite=ET.parse(ROOT/'server/egov-backend/target/surefire-reports/TEST-com.smartbis.backend.tago.ArrivalCandidateSqlFixtureTest.xml').getroot()
        result['java_selector_counts']={k:int(suite.get(k,'0')) for k in ('tests','failures','errors','skipped')}
        assert result['java_selector_counts']==dict(tests=14,failures=0,errors=0,skipped=0)
        exit_code = 0
    except Exception as failure:
        result['failures'].append(str(failure))
    finally:
        if result['created']:
            try:
                psql('postgres', 'DROP DATABASE "' + DB + '";')
                result['deleted'] = psql('postgres', "SELECT count(*) FROM pg_database WHERE datname='" + DB + "';") == '0'
            except Exception as failure:
                result['failures'].append('DROP_FAILED: ' + str(failure))
                exit_code = 1
        result['schema_original_unchanged'] = hashlib.sha256(schema.read_bytes()).hexdigest() == schema_before
        result['exit'] = exit_code
        (OUT / 'odd-allowed-sql-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
