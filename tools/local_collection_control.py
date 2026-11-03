"""Explicit local-only preparation/approval. Import and prepare never start anything."""
import argparse
import datetime as dt
import hashlib
import json
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

def compose_args(root=ROOT):
    return ['podman','compose','--env-file',str(root/'.tmp/local-collection.env'),
        '-p','smartbis-local-collection','-f',str(root/'compose.yaml'),'--profile','local-collection',
        'up','-d','--no-deps','--force-recreate','--build','local-collection-backend']

def run_logged(args,seconds,path):
    with path.open('wb') as log:
        result=subprocess.run([admin.TIMEOUT,str(seconds),*args],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=seconds+5)
    if result.returncode:raise ValueError('COMMAND_FAILED_EXIT_'+str(result.returncode)+'_SEE_LOCAL_LOG')

def up():
    # Only invoked on a separate user command after review/approval.
    run=validated_run()
    write_switch(CONTROL,'off')
    values=admin.read_env(admin.ENV)
    db=admin.Database(values)  # loopback Podman + exact copy DB checks before writes
    db.apply((ROOT/'server/egov-backend/local-collection/run-schema.sql').read_text(encoding='utf-8'))
    run_logged(['mvn.cmd','-B','-ntp','-f','server/egov-backend/pom.xml','-DskipTests','package'],600,ROOT/'.tmp/local-control-build.log')
    run_logged(compose_args(),600,ROOT/'.tmp/local-control-compose.log')
    print('backend_up_command_completed; switch=OFF; run='+run['collection_run_id'])
    print('Actual ready state must be checked separately; no enable command was executed.')

def main():
    parser=argparse.ArgumentParser()
    sub=parser.add_subparsers(dest='action',required=True)
    p=sub.add_parser('prepare');p.add_argument('--new-run',action='store_true')
    sub.add_parser('up');sub.add_parser('status');sub.add_parser('disable')
    p=sub.add_parser('enable');p.add_argument('--run-id',required=True);p.add_argument('--confirm-g6-g8',action='store_true')
    args=parser.parse_args()
    if args.action=='prepare':print(json.dumps(prepare(new_run=args.new_run),ensure_ascii=False))
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
