"""Explicit local-only preparation/approval. Import and prepare never start anything."""
import argparse
import datetime as dt
import hashlib
import json
import re
import secrets
import os
from pathlib import Path
import subprocess
import sys
import uuid

import local_collection_admin as admin

ROOT=Path(__file__).resolve().parent.parent
CONTROL=ROOT/'.tmp/local-collection-control'
SIX=('TAGO_ENABLED','TAGO_VEHICLE_LOCATION_ENABLED','TAGO_ARRIVAL_ENABLED',
     'TAGO_LOCATION_OBSERVATION_MODE','TAGO_CATALOG_SYNC_CRON','PUBLIC_BASE_URL')

def settings_hash(root=ROOT):
    values=admin.read_env(root/'.tmp/local-collection.env')
    admin.validate(values)
    if any(values.get(k)!=admin.SETTINGS[k] for k in SIX):
        raise ValueError('LOCAL_SIX_SETTINGS_MISMATCH')
    # No credentials, connection URLs or service keys are included in this hash.
    policy={k:values.get(k,'') for k in admin.SETTINGS}
    policy['LOCAL_COLLECTION_STOP_AT']=values.get('LOCAL_COLLECTION_STOP_AT','')
    payload=json.dumps(policy,sort_keys=True).encode()
    for name in ('application-local-collection.properties','rev17-routes.csv','rev17-departures.csv'):
        payload+=(root/'server/egov-backend/src/main/resources'/name).read_bytes()
    return hashlib.sha256(payload).hexdigest()

def commit_hash(root=ROOT):
    result=subprocess.run([admin.TIMEOUT,'20','git','rev-parse','HEAD'],cwd=root,capture_output=True,timeout=25)
    value=result.stdout.decode().strip()
    if result.returncode or len(value)!=40 or any(c not in '0123456789abcdef' for c in value):
        raise ValueError('COMMIT_HASH_UNAVAILABLE')
    return value

def atomic_json(path,data):
    staging=path.with_suffix(path.suffix+'.tmp')
    staging.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    staging.replace(path)

def write_switch(directory,value):
    staging=directory/'enabled.tmp'
    staging.write_text(value+'\n',encoding='utf-8');staging.replace(directory/'enabled')

def prepare(directory=CONTROL,*,new_run=False,root=ROOT):
    config=settings_hash(root)
    commit=commit_hash(root)
    directory.mkdir(parents=True,exist_ok=True)
    descriptor=directory/'run.json'
    if descriptor.exists() and not new_run:
        raise ValueError('RUN_EXISTS_USE_STATUS_OR_EXPLICIT_NEW_RUN')
    if descriptor.exists():
        old=json.loads(descriptor.read_text(encoding='utf-8'))
        previous=str(uuid.UUID(old['collection_run_id']))
        history=directory/'history';history.mkdir(exist_ok=True)
        target=history/(previous+'.json')
        if not target.exists():target.write_text(json.dumps(old,indent=2),encoding='utf-8')
    write_switch(directory,'off')
    run={'collection_run_id':str(uuid.uuid4()),'prepared_at':dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00','Z'),
         'commit_hash':commit,'config_hash':config}
    atomic_json(descriptor,run)
    atomic_json(directory/'ready.json',{'status':'BACKEND_NOT_PREPARED'})
    return run

def validated_run(directory=CONTROL,root=ROOT):
    run=json.loads((directory/'run.json').read_text(encoding='utf-8'))
    if uuid.UUID(run['collection_run_id']).int==0:raise ValueError('LEGACY_RUN_NOT_ALLOWED')
    if run['commit_hash']!=commit_hash(root) or run['config_hash']!=settings_hash(root):
        raise ValueError('RUN_COMMIT_OR_CONFIG_CHANGED_PREPARE_NEW_RUN')
    return run

def enable(run_id,confirmed,directory=CONTROL,root=ROOT):
    if not confirmed:raise ValueError('G6_G7_G8_CONFIRMATION_REQUIRED')
    run=validated_run(directory,root)
    if str(uuid.UUID(run_id))!=run['collection_run_id']:raise ValueError('RUN_ID_MISMATCH')
    ready=json.loads((directory/'ready.json').read_text(encoding='utf-8'))
    if any(ready.get(k)!=run[k] for k in ('collection_run_id','commit_hash','config_hash')):
        raise ValueError('BACKEND_RUN_NOT_PREPARED')
    write_switch(directory,run['collection_run_id'])

def local_backend_auth(root=ROOT):
    directory=root/'.tmp/local-collection-control';directory.mkdir(parents=True,exist_ok=True)
    path=directory/'backend-auth.env'
    if not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w',encoding='utf-8') as stream:
            stream.write('DASHBOARD_ADMIN_USERNAME=local-'+secrets.token_hex(8)+'\n')
            stream.write('DASHBOARD_ADMIN_PASSWORD='+secrets.token_urlsafe(36)+'\n')
    values=admin.read_env(path)
    if any(not values.get(k) for k in ('DASHBOARD_ADMIN_USERNAME','DASHBOARD_ADMIN_PASSWORD')):
        raise ValueError('LOCAL_BACKEND_AUTH_INCOMPLETE')
    return path

def write_local_compose(root=ROOT):
    # Compose interpolates inactive services too. Project only the one approved
    # service; never supply fake operational credentials or read root dotenv.
    lines=(root/'compose.yaml').read_text(encoding='utf-8-sig').splitlines()
    starts=[i for i,line in enumerate(lines) if line=='  local-collection-backend:']
    if len(starts)!=1:raise ValueError('LOCAL_COMPOSE_SERVICE_NOT_UNIQUE')
    start=starts[0];end=len(lines)
    for i in range(start+1,len(lines)):
        if lines[i] and not lines[i].startswith((' ','#')) or re.match(r'^  [A-Za-z][\w-]*:',lines[i]):
            end=i;break
    block='\n'.join(lines[start:end]).rstrip()+'\n'
    if '${' in block or '&' in block or '*' in block:raise ValueError('LOCAL_COMPOSE_MUST_BE_SELF_CONTAINED')
    replacements={
        'context: ./server/egov-backend':'context: '+json.dumps((root/'server/egov-backend').resolve().as_posix()),
        '- ./.tmp/local-collection.env':'- '+json.dumps((root/'.tmp/local-collection.env').resolve().as_posix()),
        '- ./.tmp/local-collection-control:/collection-control':'- '+json.dumps((root/'.tmp/local-collection-control').resolve().as_posix()+':/collection-control'),
    }
    for old,new in replacements.items():
        if block.count(old)!=1:raise ValueError('LOCAL_COMPOSE_PATH_LAYOUT_CHANGED')
        block=block.replace(old,new)
    directory=root/'.tmp/local-collection-control';directory.mkdir(parents=True,exist_ok=True)
    auth=local_backend_auth(root)
    local_env='- '+json.dumps((root/'.tmp/local-collection.env').resolve().as_posix())
    block=block.replace(local_env,local_env+'\n      - '+json.dumps(auth.resolve().as_posix()))
    path=directory/'compose.local.yaml'
    path.write_text('services:\n'+block,encoding='utf-8')
    return path

def compose_args(root=ROOT):
    return ['podman','compose','--env-file',str(root/'.tmp/local-collection.env'),
        '-p','smartbis-local-collection','-f',str(root/'.tmp/local-collection-control/compose.local.yaml'),'--profile','local-collection',
        'up','-d','--no-deps','--force-recreate','--build','local-collection-backend']

def run_logged(args,seconds,path):
    result=subprocess.run([admin.TIMEOUT,str(seconds),*args],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=seconds+5)
    output=result.stdout.decode('utf-8',errors='replace')
    private=admin.read_env(admin.ENV)
    auth=CONTROL/'backend-auth.env'
    if auth.exists():private.update(admin.read_env(auth))
    for key,value in private.items():
        if value and any(word in key.upper() for word in ('PASSWORD','SERVICE_KEY','USERNAME','DATASOURCE_URL')):
            output=output.replace(value,'[비공개]')
    output=re.sub(r'''(?i)(?:https?|jdbc:postgresql|ssh)://[^\s'"<>]+''','[주소 생략]',output)
    path.write_text(output,encoding='utf-8')
    if result.returncode:raise ValueError('COMMAND_FAILED_EXIT_'+str(result.returncode)+'_SEE_LOCAL_LOG')

def schema_ready(db):
    if db.query("SELECT to_regclass('public.collection_runs') IS NOT NULL;")!='t':return False
    return db.query("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('bus_positions','arrival_predictions','collection_pause_intervals','collection_process_runs') AND column_name='collection_run_id';")=='4'

def migration_targets(root=ROOT):
    # Deliberate allowlist, never scan/apply Flyway migrations.
    return [root/'server/egov-backend/local-collection/run-schema.sql']

def validate_migration_targets(paths,root=ROOT):
    if any(re.match(r'^V0*20(?:__|[_.-]|$)',Path(p).name,re.I) for p in paths):
        raise ValueError('V020_TARGET_FORBIDDEN')
    expected=(root/'server/egov-backend/local-collection/run-schema.sql').resolve()
    if len(paths)!=1 or Path(paths[0]).resolve()!=expected:raise ValueError('UNAPPROVED_MIGRATION_TARGET')
    if not expected.is_file():raise ValueError('MIGRATION_SQL_MISSING')

def verify_backup(receipt_path,root=ROOT):
    receipt_path=Path(receipt_path).resolve()
    if (root/'.tmp').resolve() not in receipt_path.parents:raise ValueError('BACKUP_RECEIPT_MUST_BE_LOCAL_TMP')
    receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
    backup=Path(receipt['backup']).resolve()
    if (root/'.tmp').resolve() not in backup.parents or receipt.get('database')!=admin.TARGET or not receipt.get('pg_restore_list_ok'):
        raise ValueError('LOCAL_COPY_BACKUP_REQUIRED')
    data=backup.read_bytes()
    if data[:5]!=b'PGDMP' or len(data)!=receipt.get('size') or hashlib.sha256(data).hexdigest()!=receipt.get('sha256'):
        raise ValueError('BACKUP_VERIFICATION_FAILED')
    return receipt

def migrate(receipt_path,root=ROOT):
    targets=migration_targets(root);validate_migration_targets(targets,root)
    verify_backup(receipt_path,root)
    if (root/'.tmp/local-collection-control/enabled').read_text().strip()!='off':raise ValueError('SWITCH_MUST_BE_OFF_FOR_MIGRATION')
    values=admin.read_env(root/'.tmp/local-collection.env');admin.validate(values)
    db=admin.Database(values)
    sql=targets[0].read_text(encoding='utf-8')
    db.apply(sql)  # Adapter enforces copy DB and a single transaction.
    if not schema_ready(db):raise ValueError('MIGRATION_POSTCHECK_FAILED')
    history=root/'.tmp/local-collection-control/schema-history.jsonl'
    with history.open('a',encoding='utf-8') as log:
        log.write(json.dumps({'applied_at':dt.datetime.now(dt.timezone.utc).isoformat(),'database':admin.TARGET,
            'file':targets[0].relative_to(root).as_posix(),'sha256':hashlib.sha256(sql.encode()).hexdigest()})+'\n')
    print('migration_applied=server/egov-backend/local-collection/run-schema.sql; postcheck=PASS')

def up():
    # Only invoked on a separate user command after review/approval.
    run=validated_run()
    write_switch(CONTROL,'off')
    values=admin.read_env(admin.ENV)
    db=admin.Database(values)  # loopback Podman + exact copy DB, SELECT only
    if not schema_ready(db):raise ValueError('RUN_SCHEMA_MISSING_RUN_SEPARATE_MIGRATE')
    write_local_compose()
    run_logged(['mvn.cmd','-B','-ntp','-f','server/egov-backend/pom.xml','-DskipTests','package'],600,ROOT/'.tmp/local-control-build.log')
    run_logged(compose_args(),600,ROOT/'.tmp/local-control-compose.log')
    print('backend_up_command_completed; switch=OFF; run='+run['collection_run_id'])
    print('Actual ready state must be checked separately; no enable command was executed.')

def main():
    parser=argparse.ArgumentParser()
    sub=parser.add_subparsers(dest='action',required=True)
    p=sub.add_parser('prepare');p.add_argument('--new-run',action='store_true')
    p=sub.add_parser('migrate');p.add_argument('--list',action='store_true');p.add_argument('--backup-receipt')
    sub.add_parser('up');sub.add_parser('status');sub.add_parser('disable')
    p=sub.add_parser('enable');p.add_argument('--run-id',required=True);p.add_argument('--confirm-g6-g8',action='store_true')
    args=parser.parse_args()
    if args.action=='prepare':print(json.dumps(prepare(new_run=args.new_run),ensure_ascii=False))
    elif args.action=='migrate':
        if args.list:
            targets=migration_targets();validate_migration_targets(targets)
            for path in targets:print(path.relative_to(ROOT).as_posix())
        elif args.backup_receipt:migrate(args.backup_receipt)
        else:raise ValueError('VERIFIED_BACKUP_RECEIPT_REQUIRED')
    elif args.action=='up':up()
    elif args.action=='enable':enable(args.run_id,args.confirm_g6_g8);print('switch=ENABLED_BY_EXPLICIT_APPROVAL')
    elif args.action=='disable':write_switch(CONTROL,'off');print('switch=OFF')
    else:
        run=validated_run();print('collection_run_id='+run['collection_run_id'])
        print('switch='+('ON' if (CONTROL/'enabled').read_text().strip()==run['collection_run_id'] else 'OFF'))
        for key in SIX:print(key+': 일치')

if __name__=='__main__':
    try:main()
    except Exception as error:
        # Do not echo exceptions from external commands or connections.
        print('중단: '+(str(error) if isinstance(error,ValueError) and str(error).isupper() else type(error).__name__))
        sys.exit(1)
