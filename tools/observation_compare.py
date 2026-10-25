#!/usr/bin/env python3
"""Read-only TAGO observation comparison; Python 3.10+, no third-party packages."""
import argparse
import calendar as month_calendar
import csv
import io
import json
import re
import subprocess
import sys
from bisect import bisect_left, bisect_right
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
PATTERN = re.compile(r'(?:ud(?:10|[1-9])|ld[1-8]|js[1-9]|gj[12]|hjs[1-8]|hgj[12])\Z')
MATCH, WRONG, MISSING, REPEATED = '일치', '불일치', '미관측', '불일치(연속 미관측)'


class InputError(ValueError):
    pass


def read_csv(path):
    raw = Path(path).read_bytes()
    for encoding in ('utf-8-sig', 'cp949'):
        try:
            return list(csv.reader(io.StringIO(raw.decode(encoding))))
        except UnicodeDecodeError:
            pass
    raise InputError(f'CSV must be UTF-8 or CP949: {path}')


def route_id(value):
    return re.sub(r'^TBS', 'TSB', value.strip().upper())


def vehicle_id(value):
    found = re.search(r'(?<!\d)(\d{4})(?:\D*)$', str(value).strip())
    if not found:
        raise InputError(f'Invalid vehicle number: {value!r}')
    return found[1]


def family(code):
    return 'local' if code.startswith(('ud', 'ld')) else '100'


@dataclass(frozen=True)
class Expected:
    minute: int
    route: str


@dataclass(frozen=True)
class Observation:
    vehicle: str
    route: str
    at: datetime
    node_order: str = ''
    source: str = 'TAGO_OBSERVATION'
    interpolated: bool = False
    node_id: str = ''


@dataclass(frozen=True)
class HolidayCalendar:
    holidays: frozenset

    def is_holiday(self, day):
        return day.weekday() >= 5 or day in self.holidays


def load_calendar(path):
    rows = read_csv(path)
    if not rows or [c.strip() for c in rows[0]] != ['date','name','kind']:
        raise InputError('Holiday calendar requires date,name,kind')
    days = set()
    for row in rows[1:]:
        if not any(row):
            continue
        if len(row) != 3 or not row[1].strip() or not row[2].strip():
            raise InputError(f'Incomplete holiday row: {row}')
        day = date.fromisoformat(row[0].strip())
        if day in days:
            raise InputError(f'Duplicate holiday date: {day}')
        days.add(day)
    return HolidayCalendar(frozenset(days))


def pattern_allowed(code, day, calendar):
    if family(code) == 'local':
        return True
    return code.startswith(('hjs', 'hgj')) == calendar.is_holiday(day)


def load_mapping(path):
    rows = read_csv(path)
    if not rows:
        raise InputError('Empty mapping')
    header = [v.strip().upper() for v in rows[0]]
    if not {'ROUTE_ID','ROUTE_NM','OBE_NM'} <= set(header):
        raise InputError('Mapping requires ROUTE_ID, ROUTE_NM, OBE_NM')
    ri, ki, ni = header.index('ROUTE_ID'), header.index('OBE_NM'), header.index('ROUTE_NM')
    keys, routes, hundred = defaultdict(set), set(), {}
    for row in rows[1:]:
        if not any(c.strip() for c in row):
            continue
        if len(row) <= max(ri, ki, ni):
            raise InputError(f'Incomplete mapping row: {row}')
        route = route_id(row[ri])
        if not re.fullmatch(r'TSB\d{9}', route):
            raise InputError(f'Invalid route: {row[ri]}')
        routes.add(route)
        if row[ni].strip().endswith('100'):
            hundred[route] = row[ni].strip()
        if row[ki].strip():
            keys[row[ki].strip()].add(route)
    if len(hundred) != 17:
        raise InputError(f'100번 계열 집계 {len(hundred)}개 (필수 17개):\n' +
                         json.dumps(hundred, ensure_ascii=False, indent=2))
    return keys, routes, hundred


def load_patterns(paths, mapping, routes, hundred, skip_incomplete=False):
    patterns, errors, excluded, warnings = {}, [], [], []
    local_routes = set(routes) - set(hundred)
    for path in paths:
        for line, row in enumerate(read_csv(path), 1):
            cells = [c.strip() for c in row]
            if not any(cells):
                continue
            indices = [i for i,c in enumerate(cells[:2]) if PATTERN.fullmatch(c.lower())]
            if not indices:
                if any(c.lower() in ('patt','pattern','패턴') for c in cells[:2]):
                    continue
                raise InputError(f'Unknown pattern row {path}:{line}: {cells[:2]}')
            idx, previous = indices[0], -1
            code = cells[idx].lower()
            if code in patterns:
                raise InputError(f'Duplicate pattern: {code}')
            entries = []
            for i in range(idx+1, len(cells), 2):
                clock, token = cells[i], cells[i+1] if i+1 < len(cells) else ''
                if not clock and not token:
                    continue
                loc = f'{path}:{line}:column {i+1}'
                if not clock or not token:
                    if skip_incomplete:
                        excluded.append({'pattern':code,'time':clock,'route_id':token,'reason':f'제외(시각/노선 공란): {loc}'})
                        continue
                    raise InputError(f'Unpaired time/route: {loc}')
                if not re.fullmatch(r'\d{1,2}:\d{2}', clock):
                    raise InputError(f'Invalid time {clock}: {loc}')
                h,m = map(int, clock.split(':'))
                if h > 23 or m > 59:
                    raise InputError(f'Invalid time {clock}: {loc}')
                moment = h*60+m
                if moment < previous:
                    warnings.append(f'시간 역전: {code} {clock}; 원본 시각 유지')
                previous = moment
                canonical = route_id(token)
                candidates = {canonical} if canonical in routes else mapping.get(token, set())
                if len(candidates) != 1:
                    errors.append({'pattern':code,'time':clock,'keycode':token,'location':loc,
                                   'reason':'not mapped' if not candidates else 'ambiguous mapping'})
                    continue
                route = next(iter(candidates))
                if route not in (hundred if family(code)=='100' else local_routes):
                    reason = ('범위 밖 노선(관내 패턴의 100번 계열 항목)' if family(code)=='local' and route in hundred
                              else '범위 밖 노선(해당 패턴 계열에 속하지 않음)')
                    excluded.append({'pattern':code,'time':clock,'route_id':route,'reason':reason})
                    continue
                entries.append(Expected(moment,route))
            patterns[code] = sorted(entries,key=lambda e:(e.minute,e.route))
    if errors:
        raise InputError('Mapping failures:\n'+json.dumps(errors,ensure_ascii=False,indent=2))
    if not patterns or any(not entries for entries in patterns.values()):
        raise InputError('No patterns or empty eligible pattern')
    return patterns, local_routes, excluded, warnings


def load_assignments(paths, month, patterns):
    result = {}
    for path in paths:
        dates = None
        for line,row in enumerate(read_csv(path),1):
            row = [v.strip() for v in row]
            if not any(row):
                continue
            try:
                vehicle = vehicle_id(row[0])
            except InputError:
                dates = {}
                for i,value in enumerate(row[1:],1):
                    if not value:
                        continue
                    try:
                        parsed = date.fromisoformat(value) if re.fullmatch(r'\d{4}-\d{2}-\d{2}',value) else date.fromisoformat(f'{month}-{int(value):02d}')
                    except ValueError as exc:
                        raise InputError(f'Invalid dispatch date {path}:{line}: {value}') from exc
                    if parsed in dates.values():
                        raise InputError(f'Duplicate dispatch date: {parsed}')
                    dates[i] = parsed
                if not dates:
                    raise InputError(f'Dispatch header has no dates: {path}:{line}')
                continue
            if dates is None:
                raise InputError(f'Missing dispatch header: {path}:{line}')
            for i,raw in enumerate(row[1:],1):
                if not raw:
                    continue
                if i not in dates:
                    raise InputError(f'Dispatch value has no date: {path}:{line}:{i+1}')
                code = raw.lower()
                if code in ('hjs','휴차','휴무'):
                    code = '휴차'
                if code != '휴차' and (code not in patterns if patterns is not None else not PATTERN.fullmatch(code)):
                    raise InputError(f'Unknown assigned pattern {raw}: {path}:{line}')
                key = (vehicle,dates[i])
                if key in result and result[key] != code:
                    raise InputError(f'Conflicting assignments: {key}: {result[key]} / {code}')
                result[key] = code
    return result


def parse_observations(rows, include_tago=False):
    result, names = [], defaultdict(set)
    for row in rows:
        source = row.get('source','TAGO_OBSERVATION')
        if source not in ({'TAGO_OBSERVATION', 'TAGO'} if include_tago else {'TAGO_OBSERVATION'}):
            continue
        vehicle = vehicle_id(row['vehicle_number'])
        names[vehicle].add(row['vehicle_number'])
        at = datetime.fromisoformat(row['observed_at'].strip().replace('Z','+00:00'))
        at = at.replace(tzinfo=KST) if at.tzinfo is None else at.astimezone(KST)
        flag=str(row.get('interpolated','false')).strip().lower()
        if flag not in ('true','false','1','0',''):
            raise InputError(f'Invalid interpolated flag: {flag}')
        result.append(Observation(vehicle,route_id(row['route_id']),at,row.get('node_order') or '',source,
                                  flag in ('true','1'),str(row.get('node_id') or row.get('nodeid') or '').strip()))
    collisions = {v:sorted(full) for v,full in names.items() if len(full)>1}
    if collisions:
        raise InputError('Full plates sharing last four digits: '+str(collisions))
    return result


def load_observations(args, first, last):
    include_tago = getattr(args, 'include_tago', False)
    if args.observations_csv:
        rows = read_csv(args.observations_csv)
        if not rows:
            raise InputError('Empty observation CSV')
        header = [s.strip() for s in rows[0]]
        if not {'vehicle_number','route_id','observed_at','node_order'} <= set(header):
            raise InputError('Observation CSV requires vehicle_number, route_id, observed_at, node_order')
        observations = parse_observations((dict(zip(header,r)) for r in rows[1:] if any(r)), include_tago)
    else:
        source_filter = "source IN ('TAGO_OBSERVATION', 'TAGO')" if include_tago else "source = 'TAGO_OBSERVATION'"
        sql = f"""BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '60s';
COPY (SELECT vehicle_number, route_id, observed_at, node_order, source, node_id FROM bus_positions
WHERE {source_filter}
AND observed_at >= '{first.isoformat()} 00:00:00+09'::timestamptz
AND observed_at < '{last.isoformat()} 00:00:00+09'::timestamptz
ORDER BY observed_at, vehicle_number, route_id) TO STDOUT WITH (FORMAT CSV, HEADER TRUE);
ROLLBACK;"""
        command = ['psql']
        if args.podman_container:
            command = ['podman','exec','-i','-e','PGCLIENTENCODING=UTF8',args.podman_container,'psql']
        command += ['-X','-q','-v','ON_ERROR_STOP=1','-U',args.db_user,'-d',args.db_name]
        if args.db_host:
            command += ['-h',args.db_host]
        if args.db_port:
            command += ['-p',str(args.db_port)]
        proc = subprocess.run(command,input=sql.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90,check=False)
        if proc.returncode:
            raise InputError('Read-only DB query failed: '+proc.stderr.decode('utf-8',errors='replace'))
        observations = parse_observations(csv.DictReader(io.StringIO(proc.stdout.decode('utf-8-sig'))), include_tago)
    return [o for o in observations if first <= o.at.date() < last]


@dataclass(frozen=True)
class InterpolationRules:
    max_gap_stops: int
    max_gap_minutes: float

    @classmethod
    def load(cls,path):
        values=json.loads(Path(path).read_text(encoding='utf-8-sig'))
        stops=values.get('max_gap_stops'); minutes=values.get('max_gap_minutes')
        if (type(stops) is not int or stops<0 or type(minutes) not in (int,float)
                or not 0<minutes<float('inf')):
            raise InputError('Invalid interpolation limits: nonnegative integer stops and positive finite minutes required')
        return cls(stops,minutes)


def interpolate_observations(observations,rules):
    """Add inferred passages between adjacent REAL observations of the same vehicle.

    max_gap_stops counts missing intermediate orders, not endpoint distance.
    Never use generated rows as endpoints or bridge a route/source switch.
    """
    originals=list(observations)
    if any(o.interpolated for o in originals):
        raise InputError('Interpolation input must contain real observations only')
    vehicles=defaultdict(list)
    for o in originals:
        vehicles[o.vehicle].append(o)
    inferred=[]; unverified=[]
    for rows in vehicles.values():
        rows=sorted(rows,key=lambda o:o.at)
        for left,right in zip(rows,rows[1:]):
            reason=''
            a=b=None
            if left.route!=right.route:
                reason='route_id 변경'
            elif left.source!=right.source:
                reason='관측 source 변경'
            elif not re.fullmatch(r'\d+',str(left.node_order)) or not re.fullmatch(r'\d+',str(right.node_order)):
                reason='node_order 미확정'
            else:
                a,b=int(left.node_order),int(right.node_order)
                gap=b-a-1
                elapsed=(right.at-left.at).total_seconds()
                if b<a:
                    reason='node_order 감소'
                elif b-a<2:
                    continue
                elif elapsed<=0:
                    reason='양의 시간 간격 없음'
                elif gap>rules.max_gap_stops:
                    reason='max_gap_stops 초과'
                elif elapsed>rules.max_gap_minutes*60:
                    reason='max_gap_minutes 초과'
                else:
                    for order in range(a+1,b):
                        inferred.append(Observation(left.vehicle,left.route,
                            left.at+(right.at-left.at)*((order-a)/(b-a)),str(order),left.source,True))
                    continue
            unverified.append(dict(vehicle=left.vehicle,route_id=left.route,next_route_id=right.route,
                start_at=left.at.isoformat(),end_at=right.at.isoformat(),start_order=left.node_order,
                end_order=right.node_order,status='구간 미확인',reason=reason))
    # Preserve original objects and duplicate observations, never modify or deduplicate.
    return sorted(originals+inferred,key=lambda o:(o.vehicle,o.at)),unverified


def load_route_stop_orders(path):
    """Read an offline stop catalog. Repeated orders count once; zero is valid."""
    rows=read_csv(path)
    if not rows or not {'route_id','nodeord_raw'} <= set(rows[0]):
        raise InputError('Stop catalog requires route_id,nodeord_raw')
    orders=defaultdict(set)
    for values in rows[1:]:
        row=dict(zip(rows[0],values))
        token=row.get('nodeord_raw','').strip()
        if re.fullmatch(r'\d+',token):
            orders[route_id(row['route_id'])].add(int(token))
    return orders


def collection_rates(observations,stop_orders,start,end):
    """Diagnostic observed-run segments, not confirmed dispatch/trip identifiers.

    Split on vehicle, KST date, route/source change or decreasing valid order.
    Actual distinct stop orders only; rates NEVER select/exclude routes.
    """
    vehicles=defaultdict(list)
    for o in observations:
        if not o.interpolated:
            vehicles[o.vehicle].append(o)
    runs=[]
    for vehicle,rows in sorted(vehicles.items()):
        group=[]; previous=None; run_no=0
        for o in sorted(rows,key=lambda r:r.at):
            order=int(o.node_order) if re.fullmatch(r'\d+',str(o.node_order)) else None
            prior=int(previous.node_order) if previous and re.fullmatch(r'\d+',str(previous.node_order)) else None
            changed=previous and (o.at.date()!=previous.at.date() or o.route!=previous.route
                                   or o.source!=previous.source or order is not None and prior is not None and order<prior)
            if changed:
                runs.append((vehicle,run_no,group)); group=[]
            if not group:
                run_no+=1
            group.append(o); previous=o
        if group:
            runs.append((vehicle,run_no,group))
    result=[]; route_groups=defaultdict(list)
    def metric(level,vehicle,run_no,rows):
        route=rows[0].route
        actual={int(o.node_order) for o in rows if re.fullmatch(r'\d+',str(o.node_order))}
        catalog=stop_orders.get(route,set())
        observed=actual & catalog if catalog else actual
        return dict(level=level,date=rows[0].at.date().isoformat(),vehicle=vehicle,run_id=run_no,
            route_id=route,start_at=min(o.at for o in rows).isoformat(),end_at=max(o.at for o in rows).isoformat(),
            observed_stop_count=len(observed),route_stop_count=len(catalog) if catalog else '',
            collection_rate_pct=round(100*len(observed)/len(catalog),2) if catalog else '',
            unmatched_order_count=len(actual-catalog) if catalog else len(actual),
            status='참고용(노선 제외에 사용하지 않음)' if catalog else '노선 정류장 수 미확인')
    for vehicle,run_no,rows in runs:
        day=rows[0].at.date()
        if start<=day<=end:
            result.append(metric('운행 구간',vehicle,run_no,rows))
            route_groups[day,rows[0].route].extend(rows)
    for _,rows in sorted(route_groups.items()):
        result.append(metric('노선/일자','','',rows))
    return result


def origin_review_lines(observations,stop_orders,start,end):
    """Use catalog minimum, never the smallest order merely seen in observations."""
    missing=defaultdict(list)
    for o in observations:
        orders=stop_orders.get(o.route,set())
        if (not o.interpolated and start<=o.at.date()<=end and orders
                and re.fullmatch(r'\d+',str(o.node_order))
                and int(o.node_order)==min(orders) and not o.node_id):
            missing[o.route,o.vehicle,o.at.date()].append(o)
    lines=['[기점 진단]',
           '출발 기준: TAGO 노선 정류장 순번 최소값 또는 운수사 시간표의 출발 기점.',
           '장성터미널 등 특정 정류장 관측 유무는 창 계산 조건이 아님. 기점 진단으로 창/후보를 제외하지 않음.',
           f'기점 좌표 검토 필요: {sum(len(v) for v in missing.values())}행 (기점 최소 순번 관측에 nodeid 없음)']
    for (route,vehicle,day),rows in sorted(missing.items()):
        lines.append(f'기점 좌표 검토 필요: {day} {route} 차량 {vehicle}, 순번 {rows[0].node_order}, '
                     f'{len(rows)}행, 최초 {min(o.at for o in rows).isoformat()} / 최종 {max(o.at for o in rows).isoformat()}')
    return lines


class ObservationIndex:
    def __init__(self, observations):
        self.rows = defaultdict(list)
        self.times = {}
        self.observed_days = set()
        for o in observations:
            if o.interpolated:
                continue
            self.rows[o.vehicle].append(o)
            self.observed_days.add(o.at.date())
        for v,rows in self.rows.items():
            rows.sort(key=lambda o:o.at)
            self.times[v] = [o.at for o in rows]

    def window(self, vehicle, moment, minutes, allowed):
        rows,times = self.rows.get(vehicle,[]),self.times.get(vehicle,[])
        delta = timedelta(minutes=minutes)
        return [o for o in rows[bisect_left(times,moment-delta):bisect_right(times,moment+delta)] if o.route in allowed]


def classify(index,vehicle,day,expected,minutes,allowed):
    moment = datetime.combine(day,time(),KST)+timedelta(minutes=expected.minute)
    routes = sorted({o.route for o in index.window(vehicle,moment,minutes,allowed)})
    return (MATCH if expected.route in routes else WRONG if routes else MISSING),routes


def evaluate(index,vehicle,day,code,expected,assignments,minutes,allowed,previous_applicable=True):
    details = []
    for item in expected:
        status,routes = classify(index,vehicle,day,item,minutes,allowed)
        previous = day-timedelta(days=1)
        if (status==MISSING and previous_applicable and day in index.observed_days and previous in index.observed_days
                and assignments.get((vehicle,day))==code and assignments.get((vehicle,previous))==code):
            before,_ = classify(index,vehicle,previous,item,minutes,allowed)
            if before==MISSING:
                status = REPEATED
        details.append({'vehicle':vehicle,'date':day.isoformat(),'pattern':code,
                        'time':f'{item.minute//60:02d}:{item.minute%60:02d}','expected_route':item.route,
                        'status':status,'observed_routes':'|'.join(routes)})
    matched = sum(d['status']==MATCH for d in details)
    wrong = sum(d['status'] in (WRONG,REPEATED) for d in details)
    missing = sum(d['status']==MISSING for d in details)
    total = len(details)
    summary = {'vehicle':vehicle,'date':day.isoformat(),'pattern':code,'expected':total,
               'matched':matched,'mismatched':wrong,'pending_missing':missing,
               'match_rate_pct':round(100*matched/(matched+wrong),2) if matched+wrong else '',
               'evidence_coverage_pct':round(100*(total-missing)/total,2),
               'matched_expected_pct':round(100*matched/total,2)}
    return summary,details


def write_csv(path,rows,fields):
    with Path(path).open('w',newline='',encoding='utf-8-sig') as out:
        writer = csv.DictWriter(out,fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def report_header(args):
    path = Path(args.holiday_calendar).resolve()
    try:
        label = path.relative_to(Path(__file__).resolve().parents[1]).as_posix()
    except ValueError:
        label = path.as_posix()
    return (f'공휴일 달력: {label} (2026, 대체공휴일 포함) | '
            '배차표 기준: 2026-08 예시, 10월 실배차 아님')


def check_schedule(args):
    if args.assignment_month != '2026-08':
        raise InputError('schedule-check-only is for the 2026-08 example schedule')
    calendar = load_calendar(args.holiday_calendar)
    assignments = load_assignments(args.dispatch, args.assignment_month, None)
    conflicts = [dict(vehicle=v, date=d.isoformat(), day_type='휴일' if calendar.is_holiday(d) else '평일',
                      assigned_pattern=code, reason='배차 상충') for (v,d),code in sorted(assignments.items())
                 if code != '휴차' and not pattern_allowed(code,d,calendar)]
    out = Path(args.output_dir)
    out.mkdir(parents=True,exist_ok=True)
    write_csv(out/'calendar_assignment_conflicts.csv',conflicts,
              ['vehicle','date','day_type','assigned_pattern','reason'])
    first = date(2026,8,1)
    days = [first+timedelta(days=i) for i in range(month_calendar.monthrange(2026,8)[1])]
    lines = [report_header(args), '실행 모드: 배차표 검사만 (관측 조회 및 판정 결과 생성 없음)',
             f'검사 배차: {len(assignments)}건',f'배차 상충: {len(conflicts)}건',
             '[8월 날짜별 평일/휴일]']
    lines += [f"{d.isoformat()} {'휴일' if calendar.is_holiday(d) else '평일'}" for d in days]
    lines += [f"배차 상충: {r['date']} {r['vehicle']} {r['assigned_pattern']}" for r in conflicts]
    text = '\n'.join(lines)+'\n'
    (out/'summary.txt').write_text(text,encoding='utf-8-sig')
    print(text)
    if conflicts:
        raise InputError(f'배차 상충 {len(conflicts)}건: 검사 중단')
    return assignments


def compare(args, observations=None):
    start,end = date.fromisoformat(args.start_date),date.fromisoformat(args.end_date or args.start_date)
    if end < start:
        raise InputError('end-date precedes start-date')
    month = args.assignment_month
    assignment_month = date.fromisoformat(month+'-01')
    mapping,routes,hundred_names = load_mapping(args.mapping)
    hundred = set(hundred_names)
    calendar = load_calendar(args.holiday_calendar)
    patterns,local_routes,excluded,warnings = load_patterns(args.patterns,mapping,routes,hundred,args.skip_incomplete)
    assignments = load_assignments(args.dispatch,month,patterns)
    if observations is None:
        observations = load_observations(args,start-timedelta(days=2),end+timedelta(days=2))
    observations = [o for o in observations if not o.interpolated]
    interpolation_path=getattr(args,'interpolation_rules',Path(__file__).parent/'config'/'interpolation_rules.json')
    interpolation_rules=InterpolationRules.load(interpolation_path)
    passages,unverified=interpolate_observations(observations,interpolation_rules)
    stops_path=getattr(args,'route_stops',Path(__file__).parents[1]/'장성교통 노선도'/'TAGO_장성군_노선별_경유정류소_20261002.csv')
    stop_orders=load_route_stop_orders(stops_path)
    rates=collection_rates(observations,stop_orders,start,end)
    origin_lines=origin_review_lines(observations,stop_orders,start,end)
    index = ObservationIndex(observations)
    out = Path(args.output_dir)
    out.mkdir(parents=True,exist_ok=True)
    write_csv(out/'route_collection_rates.csv',rates,
        ['level','date','vehicle','run_id','route_id','start_at','end_at','observed_stop_count',
         'route_stop_count','collection_rate_pct','unmatched_order_count','status'])
    period_passages=[o for o in passages if start<=o.at.date()<=end]
    write_csv(out/'observations_with_interpolation.csv',[
        dict(vehicle=o.vehicle,route_id=o.route,observed_at=o.at.isoformat(),node_order=o.node_order,
             source=o.source,interpolated='true' if o.interpolated else 'false',node_id=o.node_id) for o in period_passages],
        ['vehicle','route_id','observed_at','node_order','source','interpolated','node_id'])
    period_unverified=[r for r in unverified if datetime.fromisoformat(r['start_at']).date()<=end
                       and datetime.fromisoformat(r['end_at']).date()>=start]
    write_csv(out/'unverified_segments.csv',period_unverified,
        ['vehicle','route_id','next_route_id','start_at','end_at','start_order','end_order','status','reason'])
    daily,candidates,recommendations,mismatches,all_details,unassigned = [],[],[],[],[],[]
    days, schedules, calendar_conflicts = [], [], []
    observation_days = defaultdict(list)
    for observation in observations:
        observation_days[observation.at.date()].append(observation)
    roster = {v for v,d in assignments}
    known_vehicles = {o.vehicle for o in observations if start <= o.at.date() <= end} | roster
    day = start
    while day <= end:
        day_type = '휴일' if calendar.is_holiday(day) else '평일'
        confirmed = args.dispatch_confirmed and (day.year,day.month) == (assignment_month.year,assignment_month.month)
        effective_assignments = assignments if confirmed else {}
        day_rows = observation_days[day]
        applicable = {code:items for code,items in patterns.items() if pattern_allowed(code,day,calendar)}
        days.append(dict(date=day.isoformat(),day_type=day_type,status='관측 있음' if day_rows else '관측 없음',
                         observation_rows=len(day_rows), vehicles=len({o.vehicle for o in day_rows}),
                         tago_observation_rows=sum(o.source=='TAGO_OBSERVATION' for o in day_rows),
                         tago_rows=sum(o.source=='TAGO' for o in day_rows)))
        schedules.extend(dict(date=day.isoformat(),day_type=day_type,pattern=code,
                              time=f'{e.minute//60:02d}:{e.minute%60:02d}',route_id=e.route)
                         for code,items in sorted(applicable.items()) for e in items)
        vehicles = sorted({o.vehicle for o in day_rows} | roster
                          | (known_vehicles if not day_rows else set()))
        for vehicle in vehicles:
            assigned = assignments.get((vehicle,day),'미배정') if confirmed else '배차 미확정'
            empty = dict(vehicle=vehicle,date=day.isoformat(),pattern='',assignment=assigned,expected=0,
                         matched=0,mismatched=0,pending_missing=0,match_rate_pct='',evidence_coverage_pct='',
                         matched_expected_pct='',day_type=day_type,assignment_status='확정 입력' if confirmed else '배차 미확정')
            if not day_rows:
                daily.append(dict(empty,status='관측 없음'))
                recommendations.append(dict(vehicle=vehicle,date=day.isoformat(),day_type=day_type,status='관측 없음',
                                             assigned_pattern=assigned,recommended_pattern='',matched_expected_pct='',
                                             evidence='관측 없음',tied_patterns=''))
                continue
            scored = []
            for code,expected in sorted(applicable.items()):
                summary,details = evaluate(index,vehicle,day,code,expected,effective_assignments,args.window_minutes,
                                           hundred if family(code)=='100' else local_routes,
                                           pattern_allowed(code,day-timedelta(days=1),calendar))
                summary.update(day_type=day_type,status='판정')
                candidates.append(summary)
                scored.append(summary)
                if code==assigned:
                    daily.append(dict(summary,assignment=assigned,assignment_status='확정 입력'))
                    all_details.extend(details)
                    mismatches.extend(d for d in details if d['status'] in (WRONG,REPEATED))
            if assigned in ('미배정','휴차','배차 미확정'):
                daily.append(dict(empty,status=assigned))
            elif assigned not in applicable:
                daily.append(dict(empty,status='배차/요일 불일치'))
                calendar_conflicts.append(dict(vehicle=vehicle,date=day.isoformat(),day_type=day_type,assigned_pattern=assigned,
                                                reason='해당 날짜에 허용되지 않는 배차 패턴; 임의 변환하지 않음'))
            if (assigned=='미배정' or not confirmed and vehicle not in roster) and any(o.vehicle==vehicle for o in day_rows):
                unassigned.append(dict(vehicle=vehicle,date=day.isoformat(),observation_rows=sum(o.vehicle==vehicle for o in day_rows),
                                       status='미배정' if confirmed else '배차 미확정(예시표 미등재)'))
            if not scored:
                recommendations.append(dict(vehicle=vehicle,date=day.isoformat(),day_type=day_type,status='후보 없음',
                                             assigned_pattern=assigned,recommended_pattern='',matched_expected_pct='',evidence='해당 날짜 후보 없음',tied_patterns=''))
                continue
            best = sorted(scored,key=lambda s:(-s['matched_expected_pct'],-s['matched'],s['mismatched'],s['pattern']))[0]
            tied = [s['pattern'] for s in scored if (s['matched_expected_pct'],s['matched'],s['mismatched']) == (best['matched_expected_pct'],best['matched'],best['mismatched'])]
            recommendations.append(dict(vehicle=vehicle,date=day.isoformat(),assigned_pattern=assigned,day_type=day_type,status='추천',
                                        recommended_pattern=best['pattern'],matched_expected_pct=best['matched_expected_pct'],
                                        evidence='관측 일치 있음' if best['matched'] else '일치 근거 없음(형식상 후보)',tied_patterns='|'.join(tied)))
        day += timedelta(days=1)
    summary_fields = ['vehicle','date','day_type','status','pattern','expected','matched','mismatched','pending_missing','match_rate_pct','evidence_coverage_pct','matched_expected_pct']
    detail_fields = ['vehicle','date','pattern','time','expected_route','status','observed_routes']
    write_csv(out/'vehicle_daily.csv',daily,summary_fields+['assignment','assignment_status'])
    write_csv(out/'pattern_candidates.csv',candidates,summary_fields)
    write_csv(out/'recommendations.csv',recommendations,['vehicle','date','day_type','status','assigned_pattern','recommended_pattern','matched_expected_pct','evidence','tied_patterns'])
    write_csv(out/'unassigned.csv',unassigned,['vehicle','date','status','observation_rows'])
    write_csv(out/'mismatches.csv',mismatches,detail_fields)
    write_csv(out/'expected_results.csv',all_details,detail_fields)
    dated_excluded = [dict(item,date=day_row['date']) for item in excluded for day_row in days
                      if pattern_allowed(item['pattern'],date.fromisoformat(day_row['date']),calendar)]
    write_csv(out/'excluded_expectations.csv',dated_excluded,['date','pattern','time','route_id','reason'])
    write_csv(out/'expected_schedule.csv',schedules,['date','day_type','pattern','time','route_id'])
    write_csv(out/'day_summary.csv',days,['date','day_type','status','observation_rows','vehicles','tago_observation_rows','tago_rows'])
    write_csv(out/'calendar_assignment_conflicts.csv',calendar_conflicts,['vehicle','date','day_type','assigned_pattern','reason'])
    write_csv(out/'hundred_routes.csv',[dict(route_id=r,route_name=n) for r,n in sorted(hundred_names.items())],['route_id','route_name'])
    js9_times = [f'{e.minute//60:02d}:{e.minute%60:02d}' for e in patterns.get('js9',[])]
    ud5_blank = any(e['pattern']=='ud5' and e['time']=='20:00' and not e['route_id'] for e in excluded)
    lines = [report_header(args),f'기간: {start} ~ {end} (KST)',f'입력 배차표 해석 월: {month}',
             f'배차 확정 옵션: {args.dispatch_confirmed} (동일 월 + 옵션 동시 충족 날짜만 배정 일치율 계산)',
             '실행 유형: 진단(--skip-incomplete)' if args.skip_incomplete else '실행 유형: 정식', '매핑 실패: 0',
             f'100번 계열: {len(hundred)}개 (대응표 ROUTE_NM 끝문자 100으로 자동 집계)',
             '100번 계열 ID: '+', '.join(sorted(hundred)),
             '관측 source: TAGO_OBSERVATION'+(' + TAGO (참고용)' if args.include_tago else ' (TAGO 제외)'),
             f'입력 경고: {len(warnings)+len(excluded)}건',
             f'패턴: {len(patterns)}, 범위 밖/공란 항목: {len(excluded)}',
             f'js9 입력 재확인: 01:10 {"있음(원본 유지)" if "01:10" in js9_times else "없음"}; 시각: '+', '.join(js9_times),
             f'ud5 20:00 노선 공란: {"있음(진단 제외)" if ud5_blank else "없음(현재 원본에서 공란 항목 발견 안 됨)"}',
             f'기간 내 관측: {sum(start <= o.at.date() <= end for o in observations)}행',
             f'보간: {sum(o.interpolated for o in period_passages)}행; 구간 미확인: {len(period_unverified)}건',
             f'보간 상한: 누락 순번 {interpolation_rules.max_gap_stops}개, {interpolation_rules.max_gap_minutes:g}분',
             '일치·불일치·미관측, 추천 점수, 관측일 및 관측 행 수는 실제 관측만 사용. 보간 행은 판정 제외.',
             f'미배정/미확정 차량 목록: {len(unassigned)}건, 배정 기반 불일치 기대 항목: {len(mismatches)}',
             '일치율 = 일치/(일치+불일치). 보류 미관측은 분모 제외; 공란은 판정 근거 없음.',
             '추천 점수 = 일치/전체 기대 항목. 추천은 참고용이며 배차/DB를 변경하지 않음.',
             '연속 미관측 승격: 같은 차량/패턴이 전날과 당일 배정되고 같은 시각/노선 모두 미관측.',
             '전날 또는 당일 전체 관측 데이터가 없으면 연속 미관측 근거 부족으로 승격하지 않음.',
             '평일 후보: 관내 + js/gj; 휴일 후보: 관내 + hjs/hgj. 휴차는 배정 판정 제외.',
             f'배차/요일 불일치: {len(calendar_conflicts)}건 (임의 변환하지 않고 별도 CSV 기록)',
             '[미배정/미확정(예시표 미등재 포함)] '+(', '.join(f"{r['date']}:{r['vehicle']}" for r in unassigned) or '없음'),'[날짜별 결과]']
    lines += [f"{r['date']} {r['day_type']}: {r['status']}, {r['observation_rows']}행, {r['vehicles']}대" for r in days]
    lines += ['[노선·운행 구간별 수집률(실제 관측만)]',f'정류장 수 출처: {stops_path}',
              '분자: 실제 관측된 서로 다른 순번(카탈로그에 있는 값); 분모: 노선 카탈로그의 서로 다른 순번.',
              '운행 구간은 차량별 날짜/노선/source 변경 또는 순번 감소로 분리한 추정 구간이며 확정 배차 운행이 아님.',
              '수집률로 노선/후보를 제외하지 않음. 정류장 수 미확인 시 비율은 공란.']
    lines += [f"{r['date']} {r['level']} {r['vehicle']} #{r['run_id']} {r['route_id']}: "
              f"{r['observed_stop_count']}/{r['route_stop_count']} "
              +(f"{r['collection_rate_pct']}%" if r['collection_rate_pct']!='' else '수집률 미산출(분모 미확인)') for r in rates]
    lines += origin_lines
    lines += ['[차량별 일치율]']
    lines += [f"{r['date']} {r['vehicle']} {r['assignment']}: {r['status']} / {r['match_rate_pct'] if r['match_rate_pct'] != '' else '판정 보류'} (일치 {r['matched']}, 불일치 {r['mismatched']}, 미관측 {r['pending_missing']}, 기대 {r['expected']})" for r in daily]
    lines += ['[입력 경고]']+warnings+[f"{r['reason']}: {r['pattern']} {r['time']} {r['route_id']}" for r in excluded]
    text = '\n'.join(lines)+'\n'
    (out/'summary.txt').write_text(text,encoding='utf-8-sig')
    print(text)
    return daily


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--patterns',nargs='+')
    parser.add_argument('--mapping')
    parser.add_argument('--dispatch',nargs='+',required=True)
    parser.add_argument('--start-date',help='YYYY-MM-DD KST inclusive')
    parser.add_argument('--end-date',help='YYYY-MM-DD KST inclusive')
    parser.add_argument('--include-tago',action='store_true',help='Also read source TAGO; default observation-only')
    parser.add_argument('--interpolation-rules',default=str(Path(__file__).parent/'config'/'interpolation_rules.json'))
    parser.add_argument('--route-stops',default=str(Path(__file__).parents[1]/'장성교통 노선도'/'TAGO_장성군_노선별_경유정류소_20261002.csv'),
                        help='Offline route_id,nodeord_raw stop catalog for diagnostic collection rates')
    parser.add_argument('--holiday-calendar',default=str(Path(__file__).parent/'config'/'holidays_2026.csv'),
                        help='CSV date,name,kind; weekends always holidays; every CSV row is a configured holiday')
    parser.add_argument('--assignment-month',default='2026-08',help='YYYY-MM for dispatch headers, default 2026-08 EXAMPLE')
    parser.add_argument('--dispatch-confirmed',action='store_true',help='Confirm input is the real schedule for assignment-month')
    parser.add_argument('--schedule-check-only',action='store_true',help='Check August example calendar conflicts only; no observations or judging')
    parser.add_argument('--window-minutes',type=int,default=15)
    parser.add_argument('--skip-incomplete',action='store_true',help='Diagnostic: exclude incomplete time/route pairs; unmapped nonempty keycodes always abort')
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--observations-csv')
    source.add_argument('--podman-container')
    source.add_argument('--psql',action='store_true')
    parser.add_argument('--db-user',default='smartbis_user')
    parser.add_argument('--db-name',default='smartbis_db')
    parser.add_argument('--db-host')
    parser.add_argument('--db-port',type=int)
    parser.add_argument('--output-dir',required=True)
    args = parser.parse_args()
    if not args.schedule_check_only and (not args.start_date or not args.patterns or not args.mapping or
                                        not (args.observations_csv or args.podman_container or args.psql)):
        parser.error('Comparison requires --start-date, --patterns, --mapping and an observation source')
    try:
        if args.window_minutes < 0:
            raise InputError('window-minutes must not be negative')
        if args.schedule_check_only:
            check_schedule(args)
        else:
            compare(args)
    except (InputError,ValueError,OSError,subprocess.TimeoutExpired) as exc:
        print(str(exc),file=sys.stderr)
        return 2
    return 0


if __name__=='__main__':
    raise SystemExit(main())
