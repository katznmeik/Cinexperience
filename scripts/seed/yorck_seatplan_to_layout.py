#!/usr/bin/env python3
"""Builds the seed layouts of the Yorck halls from yorck.de seat plans.

Input: a crawl folder with, per hall, `<cinema-slug>/hall-<yorck hall name>.seatplan.json`
(Yorck's seat plan from the seat selection page: areas, rows, seats) and
`hall-<name>.dom.json` (the box of every drawn seat, with its "Row r, Seat(s) c" aria label
carrying the seat's RowIndex/ColumnIndex). The crawl is never committed.

What comes from where:
- Row labels (`PhysicalName`), seat numbers (`Id`) and seat kinds come from the seat plan:
  OriginalStatus 3 = wheelchair space (W), 7 = companion seat (C), 5 = permanently blocked
  (a normal seat), SeatStyle 1/3 = the halves of a sofa (F).
- The grid comes from the drawn page: seat pitch, columns and rows, spacer rows for wide
  vertical gaps; a second area (balcony) keeps the gap yorck.de draws above it (at least one spacer row).
- Aisles (`|`): within a section (seat rows between spacer rows), a column that is empty in
  every row and lies between seats in at least two rows is an aisle; in each row, the empty
  run between two seats that contains such a column becomes `|`. Every other empty cell is a
  gap (`.`). Where the seed already has a layout with the same seats in the same places, its
  gaps and aisles are kept (hand-made aisles survive regeneration).
- Numbering: the direction per row from Yorck's numbers; `blockStarts` only where a block
  doesn't get its number automatically; a jump inside a row (a missing seat number) turns
  the empty cells between the two seats into `|` so the next block can carry its start;
  non-numeric seat labels ("R") become `seatLabels`.
- Kind overrides (`KIND_OVERRIDES`): where Yorck's data has the wrong kind for a whole hall,
  the seed's layout gets the documented kind instead; the fixture keeps Yorck's kinds.
- Screen: a hall that has no screen size in the seed gets screenWidth = columns and
  screenDistance = 1.5 (yorck.de always draws the screen as wide as the seating, so its
  drawing says nothing about the real screen). Existing values are kept.

Output: the layouts are written into the seed file (other fields untouched; bump
`seedVersion` by hand) and a fixture with Yorck's row labels, seat numbers and kinds
(taken from the seat plan alone) for YorckSeedTest.

Usage (Python 3, standard library only; run from the repository root; -I keeps Python from
importing anything from the current folder):
  python3 -I scripts/seed/yorck_seatplan_to_layout.py <crawl dir> \
      core/data/src/main/assets/seed/yorck.json \
      core/data/src/test/resources/seed/yorck-seat-numbers.json
"""
import json
import os
import re
import statistics
import sys

# yorck.de (cinema folder, hall name) -> seedKey.
HALLS = {
    ('babylon-kreuzberg', 'A'): 'babylon-kreuzberg/saal-1',
    ('babylon-kreuzberg', 'B'): 'babylon-kreuzberg/saal-2',
    ('blauer-stern', '1'): 'blauer-stern/saal-1',
    ('blauer-stern', '2'): 'blauer-stern/saal-2',
    ('capitol-dahlem', 'A'): 'capitol-dahlem/saal-1',
    ('cinema-paris', 'CP'): 'cinema-paris/saal-1',
    ('delphi-filmpalast', 'Delphi Filmpalast'): 'delphi-filmpalast/saal-1',
    **{('delphi-lux', str(n)): f'delphi-lux/saal-{n}' for n in range(1, 7)},
    ('delphi-lux', 'Luxbox'): 'delphi-lux/saal-7',
    **{('filmtheater-am-friedrichshain', str(n)): f'filmtheater-am-friedrichshain/saal-{n}' for n in range(1, 6)},
    **{('kant-kino', f'Saal {n}'): f'kant-kino/saal-{n}' for n in range(1, 6)},
    ('kino-international', '1'): 'international/saal-1',
    ('neues-off', 'Kino 1'): 'neues-off/saal-1',
    ('odeon', '1'): 'odeon/saal-1',
    **{('passage', str(n)): f'passage/saal-{n}' for n in range(1, 5)},
    **{('rollberg', str(n)): f'rollberg/saal-{n}' for n in range(1, 6)},
    ('yorck', 'Yorck'): 'yorck/yorck',
    ('yorck', 'New Yorck'): 'yorck/new-yorck',
}

# seedKey -> the kind of every place in that hall's seed layout, where Yorck's data is wrong
# (spec §9). Only the layout changes; the fixture keeps Yorck's kinds, and YorckSeedTest
# names every override as an explicit exception.
KIND_OVERRIDES = {
    # Kant Kino Saal 3 is a room of 11 double sofas upholstered like cinema seats along a
    # centre aisle (kinokompendium.de and kino-zeit.de describe it so); Yorck's data lists
    # each sofa as an unnamed row of two normal seats. All 22 places are sofa places.
    'kant-kino/saal-3': 'F',
}

NEW_SCREEN_DISTANCE = 1.5
ARIA = re.compile(r'Row (\d+), Seat\(s\) (\d+) - ')


def kind(seat):
    if seat['SeatStyle'] in (1, 3):
        return 'F'
    return {3: 'W', 7: 'C'}.get(seat['OriginalStatus'], 'S')  # 5 = permanently blocked: a normal seat


def plan_rows(plan):
    """Yorck's rows from the seat plan alone, nearest the screen first: areas in plan order
    (stalls, then balcony), rows by RowIndex descending, seats by ColumnIndex descending
    (yorck.de draws the highest RowIndex at the top and the highest ColumnIndex on the left)."""
    rows = []
    for area in plan['Areas']:
        area_rows = [r for r in area['Rows'] if r['Seats']]
        area_rows.sort(key=lambda r: -r['Seats'][0]['Position']['RowIndex'])
        for r in area_rows:
            assert len({s['Position']['RowIndex'] for s in r['Seats']}) == 1
            seats = sorted(r['Seats'], key=lambda s: -s['Position']['ColumnIndex'])
            rows.append({'area': area['Number'], 'label': r['PhysicalName'],
                         'seats': [s['Id'] for s in seats], 'kinds': ''.join(kind(s) for s in seats)})
    return rows


def drawn_rows(plan, dom):
    """Seats with their drawn centres, grouped into rows (top = nearest the screen)."""
    boxes = {}
    for e in dom['seats']:
        m = ARIA.match(e.get('aria') or '')
        if m:
            boxes.setdefault((int(m.group(1)), int(m.group(2))), []).append((e['x'] + e['w'] / 2, e['y'] + e['h'] / 2))
    for v in boxes.values():
        v.sort(key=lambda p: p[1])  # a key used by two areas: the first area is drawn higher up
    used = {}
    rows = []
    for area in plan['Areas']:
        for r in area['Rows']:
            if not r['Seats']:
                continue
            seats = []
            for s in r['Seats']:
                key = (s['Position']['RowIndex'], s['Position']['ColumnIndex'])
                i = used.get(key, 0)
                used[key] = i + 1
                x, y = boxes[key][i]
                seats.append({'id': s['Id'], 'kind': kind(s), 'x': x, 'y': y})
            seats.sort(key=lambda s: s['x'])
            y = statistics.median(s['y'] for s in seats)
            assert max(abs(s['y'] - y) for s in seats) < 2, f"row {r['PhysicalName']} is not drawn on one line"
            rows.append({'area': area['Number'], 'label': r['PhysicalName'], 'y': y, 'seats': seats})
    assert sum(len(v) for v in boxes.values()) == sum(used.values()), 'drawn seats and plan seats differ'
    rows.sort(key=lambda r: (r['area'], r['y']))
    return rows


def grid(rows):
    """Columns and spacer rows from the drawing. Returns grid rows: None = spacer."""
    diffs = [b['x'] - a['x'] for r in rows for a, b in zip(r['seats'], r['seats'][1:])]
    pitch_x = min(d for d in diffs if d > 0.6 * statistics.median(diffs))
    x0 = min(s['x'] for r in rows for s in r['seats'])
    for r in rows:
        for s in r['seats']:
            c = (s['x'] - x0) / pitch_x
            assert abs(c - round(c)) < 0.2, f"seat off the column grid in row {r['label']}: {c:.2f}"
            s['col'] = round(c)
    same_area = [(a, b) for a, b in zip(rows, rows[1:]) if a['area'] == b['area']]
    pitch_y = statistics.median(b['y'] - a['y'] for a, b in same_area)
    out = []
    for i, r in enumerate(rows):
        if i:
            prev = rows[i - 1]
            gap = (r['y'] - prev['y']) / pitch_y
            assert gap > 0.5, f'rows {prev["label"]} and {r["label"]} overlap'
            # A balcony keeps the gap yorck.de draws above it, at least one spacer row.
            spacers = round(gap) - 1
            out += [None] * (max(1, spacers) if prev['area'] != r['area'] else max(0, spacers))
        out.append(r)
    return out


def cells_of(rows, width):
    """Cell strings with seats and gaps, then aisles per section."""
    cells = [['.'] * width for r in rows]
    for r, c in zip(rows, cells):
        if r:
            for s in r['seats']:
                c[s['col']] = s['kind']
    sections, current = [], []
    for i, r in enumerate(rows):
        if r is None:
            if current:
                sections.append(current)
            current = []
        else:
            current.append(i)
    if current:
        sections.append(current)
    for section in sections:
        if len(section) < 2:
            continue
        aisle_cols = set()
        for col in range(width):
            if any(cells[i][col] != '.' for i in section):
                continue
            between = sum(1 for i in section if any(ch != '.' for ch in cells[i][:col]) and any(ch != '.' for ch in cells[i][col + 1:]))
            if between >= 2:
                aisle_cols.add(col)
        for i in section:
            row = cells[i]
            seat_cols = [c for c, ch in enumerate(row) if ch != '.']
            for a, b in zip(seat_cols, seat_cols[1:]):
                if b - a > 1 and aisle_cols & set(range(a + 1, b)):
                    for c in range(a + 1, b):
                        row[c] = '|'
    return cells


def numbering(cells, numbers):
    """Direction, blockStarts and seatLabels that give Yorck's numbers (seats left to right).
    May turn the gap between two seats into an aisle where the numbers jump."""
    seat_cols = [c for c, ch in enumerate(cells) if ch not in '.|']
    assert len(seat_cols) == len(numbers)
    numeric = [int(n) for n in numbers if n.isdigit()]
    rtl = len(numeric) > 1 and numeric[0] > numeric[-1]
    order = list(reversed(range(len(seat_cols)))) if rtl else list(range(len(seat_cols)))
    prev = None
    for i in order:
        if not numbers[i].isdigit():
            continue
        if prev is not None and int(numbers[i]) != int(numbers[prev]) + 1:
            lo, hi = sorted((seat_cols[prev], seat_cols[i]))
            between = range(lo + 1, hi)
            assert between and all(cells[c] in '.|' for c in between), f'numbers jump between adjacent seats: {numbers}'
            for c in between:
                cells[c] = '|'
        prev = i
    # Blocks (split by aisles), left to right, as lists of seat indexes.
    blocks, current, aisle = [], [], False
    seat_index = {c: k for k, c in enumerate(seat_cols)}
    for c, ch in enumerate(cells):
        if ch == '|':
            aisle = bool(current) or aisle
        elif ch != '.':
            if aisle and current:
                blocks.append(current)
                current = []
            aisle = False
            current.append(seat_index[c])
    if current:
        blocks.append(current)
    starts = [None] * len(blocks)
    derived = [None] * len(seat_cols)
    last = None
    for b in (reversed(range(len(blocks))) if rtl else range(len(blocks))):
        first = True
        for k in (reversed(blocks[b]) if rtl else blocks[b]):
            if not numbers[k].isdigit():
                derived[k] = numbers[k]
                continue
            n = int(numbers[k])
            if first:
                auto = 1 if last is None else last + 1
                if n != auto:
                    starts[b] = n
            else:
                assert n == last + 1, f'numbers jump inside a block: {numbers}'
            derived[k] = str(n)
            last = n
            first = False
    assert derived == numbers, (derived, numbers)
    while starts and starts[-1] is None:
        starts.pop()
    labels = [None if n.isdigit() else n for n in numbers]
    return rtl, starts, (labels if any(labels) else None)


def keep_existing_marks(cells, existing):
    """Takes the existing seed layout's gaps and aisles if its seats (of any kind) are in the
    same places; returns whether it did. Changes nothing otherwise."""
    if not existing or len(existing['rows']) != len(cells):
        return False
    old = [r['cells'] for r in existing['rows']]

    def seats(row):
        return [c for c, ch in enumerate(row) if ch not in '.|']

    if any(len(o) != len(c) or seats(o) != seats(c) for o, c in zip(old, cells)):
        return False
    for o, c in zip(old, cells):
        for k, ch in enumerate(o):
            if ch in '.|':
                c[k] = ch
    return True


def convert(plan_path, existing_layout, kind_override=None):
    plan = json.load(open(plan_path, encoding='utf-8'))['SeatLayoutData']
    dom = json.load(open(plan_path.replace('.seatplan.json', '.dom.json'), encoding='utf-8'))
    rows = grid(drawn_rows(plan, dom))
    if kind_override:
        for r in rows:
            for s in (r['seats'] if r else []):
                s['kind'] = kind_override
    width = max(s['col'] for r in rows if r for s in r['seats']) + 1
    cells = cells_of(rows, width)
    kept = keep_existing_marks(cells, existing_layout)
    out_rows, formats = [], {1}
    for r, c in zip(rows, cells):
        if r is None:
            out_rows.append({'cells': ''.join(c)})
            continue
        numbers = [s['id'] for s in r['seats']]
        rtl, starts, labels = numbering(c, numbers)
        row = {'label': r['label'], 'cells': ''.join(c)}
        if rtl:
            row['direction'] = 'RIGHT_TO_LEFT'
        if starts:
            row['blockStarts'] = starts
        if labels:
            row['seatLabels'] = labels
        if rtl or labels:
            formats.add(3)
        if any(ch in 'WCF' for ch in c):
            formats.add(2)
        out_rows.append(row)
    layout = {'formatVersion': max(formats), 'rows': out_rows}
    return layout, width, plan_rows(plan), kept


# --- seed file formatting: two-space indent, one layout row per line -------------------

def dump(value, indent=0, key=None):
    """JSON with two-space indent; a layout's rows go on one line each."""
    pad = '  ' * indent
    if isinstance(value, dict):
        items = [f'{pad}  {json.dumps(k, ensure_ascii=False)}: {dump(v, indent + 1, k)}' for k, v in value.items()]
        return '{\n' + ',\n'.join(items) + f'\n{pad}}}'
    if isinstance(value, list):
        if not value:
            return '[]'
        if key == 'rows':
            return '[\n' + ',\n'.join(f'{pad}  {inline(v)}' for v in value) + f'\n{pad}]'
        return '[\n' + ',\n'.join(f'{pad}  {dump(v, indent + 1)}' for v in value) + f'\n{pad}]'
    return json.dumps(value, ensure_ascii=False)


def inline(value):
    if isinstance(value, dict):
        return '{ ' + ', '.join(f'{json.dumps(k)}: {inline(v)}' for k, v in value.items()) + ' }'
    if isinstance(value, list):
        return '[' + ', '.join(inline(v) for v in value) + ']'
    return json.dumps(value, ensure_ascii=False)


def main():
    crawl, seed_path, fixture_path = sys.argv[1:4]
    assert set(KIND_OVERRIDES) <= set(HALLS.values()), 'a kind override names an unknown hall'
    seed = json.load(open(seed_path, encoding='utf-8'))
    halls = {h['seedKey']: h for c in seed['cinemas'] for h in c['halls']}
    fixture = {}
    for (cinema, name), key in HALLS.items():
        plan_path = os.path.join(crawl, cinema, f'hall-{name}.seatplan.json')
        hall = halls[key]
        layout, width, yorck_rows, kept = convert(plan_path, hall.get('layout'), KIND_OVERRIDES.get(key))
        rebuilt = {'seedKey': hall['seedKey'], 'name': hall['name'],
                   'screenWidth': hall.get('screenWidth', float(width)),
                   'screenDistance': hall.get('screenDistance', NEW_SCREEN_DISTANCE)}
        if 'screenOffset' in hall:
            rebuilt['screenOffset'] = hall['screenOffset']
        rebuilt['layout'] = layout
        rebuilt.update({k: v for k, v in hall.items() if k not in rebuilt})
        hall.clear()
        hall.update(rebuilt)
        fixture[key] = [{'row': r['label'], 'seats': r['seats'], 'kinds': r['kinds']} for r in yorck_rows]
        seats = sum(len(r['seats']) for r in yorck_rows)
        print(f'{key:40s} {seats:4d} places, {len(yorck_rows):2d} rows, {width:2d} columns, '
              f'format {layout["formatVersion"]}{", kept existing gaps/aisles" if kept else ""}'
              f'{", all places " + KIND_OVERRIDES[key] if key in KIND_OVERRIDES else ""}')
    missing = [k for k, h in halls.items() if 'layout' not in h]
    assert not missing, f'halls without a layout: {missing}'
    with open(seed_path, 'w', encoding='utf-8') as f:
        f.write(dump(seed) + '\n')
    os.makedirs(os.path.dirname(fixture_path) or '.', exist_ok=True)
    with open(fixture_path, 'w', encoding='utf-8') as f:
        f.write('{\n')
        f.write('  "source": "yorck.de seat plans, October 2026: per hall, the seat rows nearest the screen first, '
                'each with its row label and its places left to right as drawn (seat text and kind: '
                'S seat, W wheelchair space, C companion seat, F sofa place)",\n')
        f.write('  "halls": {\n')
        entries = []
        for key, rows in fixture.items():
            lines = ',\n'.join(
                f'      {{ "row": {json.dumps(r["row"], ensure_ascii=False)}, "seats": {json.dumps(r["seats"])}, '
                f'"kinds": {json.dumps(r["kinds"])} }}' for r in rows)
            entries.append(f'    {json.dumps(key)}: [\n{lines}\n    ]')
        f.write(',\n'.join(entries) + '\n  }\n}\n')


if __name__ == '__main__':
    main()
