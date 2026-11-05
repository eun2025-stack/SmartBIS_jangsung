"""Pure assignment candidates. Inputs are configuration/fixtures, never CSV or DB.

Candidates are unconfirmed plans, not observed route determinations.
"""
from datetime import date, timedelta


def sunday(day: date) -> date:
    return day - timedelta(days=(day.weekday() + 1) % 7)


def holiday(day: date, holidays=frozenset()) -> bool:
    return day.weekday() >= 5 or day in holidays


def events_before(day: date, holidays, want_holiday: bool) -> int:
    """Counting from a fixed mathematical epoch allows backward evaluation."""
    epoch = date(1970, 1, 1)
    if day < epoch:
        raise ValueError('DATES_BEFORE_1970_NOT_SUPPORTED')
    days = (day - epoch).days
    weeks, tail = divmod(days, 7)
    weekends = weeks * 2 + sum((epoch + timedelta(days=weeks * 7 + i)).weekday() >= 5 for i in range(tail))
    extra = sum(epoch <= h < day and h.weekday() < 5 for h in holidays)
    return weekends + extra if want_holiday else days - weekends - extra


def candidate(day, anchor, division, seed, slots, *, planned=True, holidays=frozenset()):
    """Return one configured slot; None is preserved as a day-off slot.

    seed indexes the initial slot at the anchor separately for each family.
    Missing plans/reserve vehicles remain unknown; they are not declared off duty.
    """
    if not planned or division == 'rb':
        return dict(pattern=None, family=None, status='PLAN_UNKNOWN')
    if division in ('ud', 'ld'):
        family = division
        shift = (sunday(day) - sunday(anchor)).days // 7
    elif division in ('js', 'gj'):
        is_holiday = holiday(day, holidays)
        family = ('h' + division) if is_holiday else division
        shift = events_before(day, holidays, is_holiday) - events_before(anchor, holidays, is_holiday)
    else:
        raise ValueError('UNKNOWN_DIVISION')
    cycle = slots.get(family, ())
    if not cycle:
        raise ValueError('PATTERN_SLOTS_REQUIRED')
    index = seed.get(family)
    if not isinstance(index, int) or not 0 <= index < len(cycle):
        raise ValueError('INITIAL_SLOT_REQUIRED')
    pattern = cycle[(index + shift) % len(cycle)]
    return dict(pattern=pattern, family=family, status='DEFAULT_DAY_OFF' if pattern is None else 'DEFAULT_UNCONFIRMED')


def assignments(vehicles, start, end, anchor, slots, holidays=frozenset()):
    if end < start:
        raise ValueError('INVALID_PERIOD')
    day = start
    while day <= end:
        for vehicle in vehicles:
            result = candidate(day, anchor, vehicle['div'], vehicle['seed'], slots,
                               planned=vehicle['planned'], holidays=holidays)
            yield dict(vehicle_no=vehicle['vehicle_no'], service_date=day.isoformat(),
                       day_type='휴일' if holiday(day, holidays) else '평일',
                       initial_state='미정', **result)
        day += timedelta(days=1)
