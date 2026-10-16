"""Tests of yorck_seatplan_to_layout.py with small invented seat plans (never crawl data).

Run from the repository root (standard library only):
  python3 -I -B -m unittest discover -s scripts/seed -p 'test_*.py'
"""
import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import warnings
from unittest import mock

# Loaded by path, so the tests also run under -I (no script folder on sys.path).
_SPEC = importlib.util.spec_from_file_location(
    'yorck_seatplan_to_layout', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'yorck_seatplan_to_layout.py'))
tool = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(tool)

PITCH_X, PITCH_Y = 20, 25


def hall(*areas):
    """An invented seat plan and its drawing, built from pictures nearest the screen first.

    Each area is a list of rows: (label, cells) or (label, cells, numbers), or None for an
    empty drawn line (a wider gap). Cells: S seat, W wheelchair space, C companion seat,
    F sofa place, B permanently blocked seat, '.' no seat. Numbers default to 1, 2, ... from
    the left. As on yorck.de, the highest RowIndex is drawn at the top and the highest
    ColumnIndex on the left; the plan lists rows and seats in the other order and has an
    empty row per area, so the tool has to sort and skip.
    """
    plan_areas, dom_seats, line = [], [], 0
    for area_number, rows in enumerate(areas, start=1):
        plan_rows = []
        for row in rows:
            if row is None:
                line += 1
                continue
            label, cells, *rest = row
            seat_cols = [c for c, ch in enumerate(cells) if ch != '.']
            numbers = rest[0] if rest else [str(n) for n in range(1, len(seat_cols) + 1)]
            assert len(numbers) == len(seat_cols)
            row_index = 100 - line
            seats, sofa_half = [], 0
            for col, number in zip(seat_cols, numbers):
                ch = cells[col]
                style = 0
                if ch == 'F':
                    style = 1 if sofa_half % 2 == 0 else 3
                    sofa_half += 1
                seats.append({'Id': number, 'SeatStyle': style, 'OriginalStatus': {'W': 3, 'C': 7, 'B': 5}.get(ch, 0),
                              'Position': {'RowIndex': row_index, 'ColumnIndex': 100 - col}})
                dom_seats.append({'aria': f'Row {row_index}, Seat(s) {100 - col} - Available',
                                  'x': 10 + PITCH_X * col, 'y': 40 + PITCH_Y * line, 'w': 16, 'h': 16})
            plan_rows.append({'PhysicalName': label, 'Seats': list(reversed(seats))})
            line += 1
        plan_rows.append({'PhysicalName': '', 'Seats': []})
        plan_areas.append({'Number': area_number, 'Rows': list(reversed(plan_rows))})
    return {'Areas': plan_areas}, {'seats': dom_seats}


def cell_rows(rows):
    """Rows of cells (lists of characters) as strings."""
    return [''.join(r) for r in rows]


def grid_of(*areas):
    plan, dom = hall(*areas)
    return tool.grid(tool.drawn_rows(plan, dom))


def ignore_resource_warnings(test):
    """The tool reads its input files without closing them; that is no failure here."""
    catcher = warnings.catch_warnings()
    catcher.__enter__()
    test.addCleanup(catcher.__exit__, None, None, None)
    warnings.simplefilter('ignore', ResourceWarning)


def write_hall(folder, name, plan, dom):
    """Writes hall-<name>.seatplan.json and .dom.json into folder; returns the plan's path."""
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, f'hall-{name}.seatplan.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'SeatLayoutData': plan}, f)
    with open(os.path.join(folder, f'hall-{name}.dom.json'), 'w', encoding='utf-8') as f:
        json.dump(dom, f)
    return path


class KindTest(unittest.TestCase):

    def seat(self, style=0, status=0):
        return {'SeatStyle': style, 'OriginalStatus': status}

    def test_both_sofa_halves_are_sofa_places_whatever_their_status(self):
        self.assertEqual('F', tool.kind(self.seat(style=1)))
        self.assertEqual('F', tool.kind(self.seat(style=3)))
        self.assertEqual('F', tool.kind(self.seat(style=1, status=3)))

    def test_wheelchair_companion_and_blocked_seats(self):
        self.assertEqual('W', tool.kind(self.seat(status=3)))
        self.assertEqual('C', tool.kind(self.seat(status=7)))
        self.assertEqual('S', tool.kind(self.seat(status=5)))  # permanently blocked: a normal seat
        self.assertEqual('S', tool.kind(self.seat()))


class PlanRowsTest(unittest.TestCase):

    def test_rows_nearest_the_screen_first_and_seats_left_to_right_with_kinds(self):
        plan, _ = hall(
            [('1', 'WSS', ['1', '2', '3']), ('2', 'SBC', ['3', '2', '1'])],
            [('R1', 'FF')],
        )
        self.assertEqual([
            {'area': 1, 'label': '1', 'seats': ['1', '2', '3'], 'kinds': 'WSS'},
            {'area': 1, 'label': '2', 'seats': ['3', '2', '1'], 'kinds': 'SSC'},
            {'area': 2, 'label': 'R1', 'seats': ['1', '2'], 'kinds': 'FF'},
        ], tool.plan_rows(plan))


class GridTest(unittest.TestCase):

    def test_columns_follow_the_seat_pitch(self):
        rows = grid_of([('1', 'S.SS'), ('2', 'SSSS')])
        self.assertEqual([[0, 2, 3], [0, 1, 2, 3]], [[s['col'] for s in r['seats']] for r in rows])

    def test_a_wider_gap_between_rows_becomes_spacer_rows(self):
        # The row pitch is the median distance, so most rows are one pitch apart.
        rows = grid_of([('1', 'SS'), ('2', 'SS'), ('3', 'SS'), ('4', 'SS'), ('5', 'SS'), None, ('6', 'SS'), None, None, ('7', 'SS')])
        self.assertEqual(['1', '2', '3', '4', '5', None, '6', None, None, '7'], [r and r['label'] for r in rows])

    def test_a_balcony_keeps_at_least_one_spacer_row(self):
        rows = grid_of([('1', 'SS'), ('2', 'SS')], [('R1', 'SS'), ('R2', 'SS')])
        self.assertEqual(['1', '2', None, 'R1', 'R2'], [r and r['label'] for r in rows])

    def test_a_seat_off_the_column_grid_fails(self):
        plan, dom = hall([('1', 'SSS'), ('2', 'SSS')])
        dom['seats'][1]['x'] += PITCH_X / 2
        with self.assertRaisesRegex(AssertionError, 'off the column grid'):
            tool.grid(tool.drawn_rows(plan, dom))

    def test_drawn_seats_must_match_the_plan(self):
        plan, dom = hall([('1', 'SS'), ('2', 'SS')])
        dom['seats'].append(dict(dom['seats'][0]))
        with self.assertRaisesRegex(AssertionError, 'drawn seats and plan seats differ'):
            tool.drawn_rows(plan, dom)


class CellsTest(unittest.TestCase):

    def cells(self, *areas):
        rows = grid_of(*areas)
        width = max(s['col'] for r in rows if r for s in r['seats']) + 1
        return cell_rows(tool.cells_of(rows, width))

    def test_an_empty_column_between_seats_in_two_rows_is_an_aisle(self):
        self.assertEqual(['SS|SS', 'SW|SS'], self.cells([('1', 'SS.SS'), ('2', 'SW.SS')]))

    def test_the_whole_empty_run_around_an_aisle_column_becomes_aisle(self):
        self.assertEqual(['SS|SS', 'S||SS'], self.cells([('1', 'SS.SS'), ('2', 'S..SS')]))

    def test_a_column_with_a_seat_in_any_row_of_the_section_is_no_aisle(self):
        self.assertEqual(['SS.SS', 'SS.SS', 'SSSSS'], self.cells([('1', 'SS.SS'), ('2', 'SS.SS'), ('3', 'SSSSS')]))

    def test_gaps_at_the_edges_stay_gaps(self):
        self.assertEqual(['SS|SS', '.S|S.'], self.cells([('1', 'SS.SS'), ('2', '.S.S.')]))

    def test_a_column_between_seats_in_one_row_only_stays_a_gap(self):
        self.assertEqual(['S.SS', '..SS'], self.cells([('1', 'S.SS'), ('2', '..SS')]))

    def test_spacer_rows_split_the_sections_and_a_lone_row_gets_no_aisle(self):
        self.assertEqual(['SS.SS', '.....', 'SS|SS', 'SS|SS', 'SS|SS'],
                         self.cells([('1', 'SS.SS'), None, ('2', 'SS.SS'), ('3', 'SS.SS'), ('4', 'SS.SS')]))


class NumberingTest(unittest.TestCase):

    def numbering(self, cells, numbers):
        cells = list(cells)
        result = tool.numbering(cells, numbers)
        return result, ''.join(cells)

    def test_numbers_from_one_left_to_right_need_nothing(self):
        self.assertEqual(((False, [], None), 'SSS'), self.numbering('SSS', ['1', '2', '3']))

    def test_falling_numbers_run_right_to_left(self):
        self.assertEqual(((True, [], None), 'SSS'), self.numbering('SSS', ['3', '2', '1']))

    def test_a_row_starting_above_one_gets_a_block_start(self):
        self.assertEqual(((False, [4], None), 'SSS'), self.numbering('SSS', ['4', '5', '6']))
        # Right to left, the start is the number of the rightmost seat.
        self.assertEqual(((True, [5], None), 'SSS'), self.numbering('SSS', ['7', '6', '5']))

    def test_numbers_continue_across_an_aisle(self):
        self.assertEqual(((False, [], None), 'SS|SS'), self.numbering('SS|SS', ['1', '2', '3', '4']))
        # Only the blocks up to the last explicit start are listed.
        self.assertEqual(((False, [3], None), 'SS|SS'), self.numbering('SS|SS', ['3', '4', '5', '6']))

    def test_numbers_restarting_after_an_aisle_get_a_block_start(self):
        self.assertEqual(((False, [None, 1], None), 'SS|SS'), self.numbering('SS|SS', ['1', '2', '1', '2']))

    def test_a_jump_turns_the_gap_between_the_seats_into_an_aisle(self):
        self.assertEqual(((False, [None, 8], None), 'SSS||SS'), self.numbering('SSS..SS', ['1', '2', '3', '8', '9']))

    def test_a_jump_between_adjacent_seats_fails(self):
        with self.assertRaisesRegex(AssertionError, 'numbers jump between adjacent seats'):
            tool.numbering(list('SSS'), ['1', '2', '5'])

    def test_text_labels_use_up_no_number(self):
        self.assertEqual(((False, [], [None, None, None, 'R']), 'SSSS'), self.numbering('SSSS', ['1', '2', '3', 'R']))
        # The example of CLAUDE.md: 6 5 4 3 2 1 R.
        self.assertEqual(((True, [], [None] * 6 + ['R']), 'SSSSSSW'),
                         self.numbering('SSSSSSW', ['6', '5', '4', '3', '2', '1', 'R']))


class KeepExistingMarksTest(unittest.TestCase):

    def test_the_gaps_and_aisles_of_a_layout_with_the_same_seats_are_kept(self):
        cells = [list('SS..SS'), list('SS..SS')]
        existing = {'rows': [{'label': '1', 'cells': 'WS|.SS'}, {'label': '2', 'cells': 'SS.|SS'}]}
        self.assertTrue(tool.keep_existing_marks(cells, existing))
        self.assertEqual(['SS|.SS', 'SS.|SS'], cell_rows(cells))  # the kinds stay new

    def test_moved_seats_a_different_shape_or_no_layout_change_nothing(self):
        for existing in (None,
                         {'rows': [{'cells': 'S.S|.S'}, {'cells': 'SS..SS'}]},
                         {'rows': [{'cells': 'SS|.SS'}]},
                         {'rows': [{'cells': 'SS|.SSS'}, {'cells': 'SS..SSS'}]}):
            cells = [list('SS..SS'), list('SS..SS')]
            self.assertFalse(tool.keep_existing_marks(cells, existing), existing)
            self.assertEqual(['SS..SS', 'SS..SS'], cell_rows(cells))


class ConvertTest(unittest.TestCase):

    def setUp(self):
        ignore_resource_warnings(self)
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)

    def convert(self, *areas, existing=None, kind_override=None):
        path = write_hall(self.folder.name, 'test', *hall(*areas))
        return tool.convert(path, existing, kind_override)

    def test_a_plain_hall_is_format_1_with_spacer_rows_of_cells_only(self):
        layout, width, yorck_rows, kept = self.convert(
            [('1', 'SS.SS'), ('2', 'SS.SS'), ('3', 'SS.SS'), None, ('4', 'SSSSS')])
        self.assertEqual({'formatVersion': 1, 'rows': [
            {'label': '1', 'cells': 'SS|SS'},
            {'label': '2', 'cells': 'SS|SS'},
            {'label': '3', 'cells': 'SS|SS'},
            {'cells': '.....'},
            {'label': '4', 'cells': 'SSSSS'},
        ]}, layout)
        self.assertEqual(5, width)
        self.assertEqual(['1', '2', '3', '4'], [r['label'] for r in yorck_rows])
        self.assertFalse(kept)

    def test_wheelchair_companion_and_sofa_places_need_format_2(self):
        for cells in ('WSS', 'SSC', 'FFS'):
            layout, *_ = self.convert([('1', cells), ('2', 'SSS')])
            self.assertEqual(2, layout['formatVersion'], cells)
            self.assertEqual(cells, layout['rows'][0]['cells'])

    def test_direction_block_starts_and_text_labels(self):
        layout, *_ = self.convert([
            ('1', 'SSS', ['3', '2', '1']),
            ('2', 'SSS..SS', ['4', '5', '6', '9', '10']),
            ('3', 'SSSS', ['1', '2', '3', 'R']),
        ])
        self.assertEqual({'formatVersion': 3, 'rows': [
            {'label': '1', 'cells': 'SSS....', 'direction': 'RIGHT_TO_LEFT'},
            {'label': '2', 'cells': 'SSS||SS', 'blockStarts': [4, 9]},
            {'label': '3', 'cells': 'SSSS...', 'seatLabels': [None, None, None, 'R']},
        ]}, layout)

    def test_a_kind_override_changes_the_layout_but_not_yorcks_rows(self):
        layout, _, yorck_rows, _ = self.convert([('-', 'SS'), ('-', 'SS')], kind_override='F')
        self.assertEqual({'formatVersion': 2, 'rows': [{'label': '-', 'cells': 'FF'}, {'label': '-', 'cells': 'FF'}]}, layout)
        self.assertEqual(['SS', 'SS'], [r['kinds'] for r in yorck_rows])

    def test_the_existing_layouts_gaps_and_aisles_are_kept(self):
        existing = {'formatVersion': 1, 'rows': [{'label': '1', 'cells': 'SS.SS'}, {'label': '2', 'cells': 'SS.SS'}]}
        layout, _, _, kept = self.convert([('1', 'SS.SS'), ('2', 'SS.SS')], existing=existing)
        self.assertTrue(kept)
        self.assertEqual(['SS.SS', 'SS.SS'], [r['cells'] for r in layout['rows']])


class DumpTest(unittest.TestCase):

    def test_two_space_indent_with_one_layout_row_per_line(self):
        seed = {'seedVersion': 3, 'cinemas': [{'name': 'Kino Ü', 'halls': [], 'layout': {
            'formatVersion': 3,
            'rows': [{'label': '1', 'cells': 'SS|SS', 'blockStarts': [None, 4]}, {'cells': '.....'}],
        }}]}
        expected = '\n'.join([
            '{',
            '  "seedVersion": 3,',
            '  "cinemas": [',
            '    {',
            '      "name": "Kino Ü",',
            '      "halls": [],',
            '      "layout": {',
            '        "formatVersion": 3,',
            '        "rows": [',
            '          { "label": "1", "cells": "SS|SS", "blockStarts": [null, 4] },',
            '          { "cells": "....." }',
            '        ]',
            '      }',
            '    }',
            '  ]',
            '}',
        ])
        self.assertEqual(expected, tool.dump(seed))
        self.assertEqual(seed, json.loads(tool.dump(seed)))


class MainTest(unittest.TestCase):
    """The whole run on an invented crawl and seed (HALLS and KIND_OVERRIDES replaced)."""

    HALLS = {('kino-x', '1'): 'kino-x/saal-1', ('kino-x', 'B'): 'kino-x/saal-2'}

    def setUp(self):
        ignore_resource_warnings(self)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = folder.name
        self.crawl = os.path.join(self.root, 'crawl')
        self.seed_path = os.path.join(self.root, 'seed.json')
        self.fixture_path = os.path.join(self.root, 'fixture', 'seat-numbers.json')
        write_hall(os.path.join(self.crawl, 'kino-x'), '1', *hall([('1', 'SS.SS'), ('2', 'SS.SS', ['4', '3', '2', '1'])]))
        write_hall(os.path.join(self.crawl, 'kino-x'), 'B', *hall([('1', 'WSSW'), ('2', 'SSSS')]))
        self.seed = {'seedVersion': 7, 'cinemas': [{'seedKey': 'kino-x', 'name': 'Kino X', 'halls': [
            {'seedKey': 'kino-x/saal-1', 'name': 'Saal 1', 'notes': 'Kept.'},
            {'seedKey': 'kino-x/saal-2', 'name': 'Saal 2', 'screenWidth': 3.0, 'screenDistance': 2.0, 'screenOffset': 0.5,
             'layout': {'formatVersion': 2, 'rows': [{'label': '1', 'cells': 'WSSW'}, {'label': '2', 'cells': 'SSSS'}]}},
        ]}]}
        self.write_seed()

    def write_seed(self):
        with open(self.seed_path, 'w', encoding='utf-8') as f:
            json.dump(self.seed, f)

    def run_main(self, halls=None, kind_overrides=None):
        argv = ['yorck_seatplan_to_layout.py', self.crawl, self.seed_path, self.fixture_path]
        with mock.patch.object(tool, 'HALLS', halls or self.HALLS), \
                mock.patch.object(tool, 'KIND_OVERRIDES', kind_overrides or {}), \
                mock.patch.object(sys, 'argv', argv), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            tool.main()
        return out.getvalue()

    def read(self, path):
        with open(path, encoding='utf-8') as f:
            return f.read()

    def test_writes_the_layouts_into_the_seed_and_keeps_everything_else(self):
        out = self.run_main()
        text = self.read(self.seed_path)
        seed = json.loads(text)
        self.assertEqual(7, seed['seedVersion'])  # bumped by hand
        new, existing = seed['cinemas'][0]['halls']
        self.assertEqual({'seedKey': 'kino-x/saal-1', 'name': 'Saal 1', 'screenWidth': 5.0, 'screenDistance': 1.5,
                          'layout': {'formatVersion': 3, 'rows': [
                              {'label': '1', 'cells': 'SS|SS'},
                              {'label': '2', 'cells': 'SS|SS', 'direction': 'RIGHT_TO_LEFT'},
                          ]}, 'notes': 'Kept.'}, new)
        self.assertEqual(['seedKey', 'name', 'screenWidth', 'screenDistance', 'layout', 'notes'], list(new))
        # A screen that isn't the grid's width, its distance and its offset stay as they are.
        self.assertEqual(['seedKey', 'name', 'screenWidth', 'screenDistance', 'screenOffset', 'layout'], list(existing))
        self.assertEqual((3.0, 2.0, 0.5), (existing['screenWidth'], existing['screenDistance'], existing['screenOffset']))
        self.assertEqual(self.seed['cinemas'][0]['halls'][1]['layout'], existing['layout'])
        self.assertTrue(text.endswith('\n'))
        self.assertIn('\n              { "label": "1", "cells": "SS|SS" },\n', text)
        self.assertIn('kino-x/saal-2', out)
        self.assertIn('kept existing gaps/aisles', out)

    def test_writes_yorcks_rows_seats_and_kinds_into_the_fixture(self):
        self.run_main()
        fixture = json.loads(self.read(self.fixture_path))
        self.assertIn('yorck.de seat plans', fixture['source'])
        self.assertEqual({
            'kino-x/saal-1': [{'row': '1', 'seats': ['1', '2', '3', '4'], 'kinds': 'SSSS'},
                              {'row': '2', 'seats': ['4', '3', '2', '1'], 'kinds': 'SSSS'}],
            'kino-x/saal-2': [{'row': '1', 'seats': ['1', '2', '3', '4'], 'kinds': 'WSSW'},
                              {'row': '2', 'seats': ['1', '2', '3', '4'], 'kinds': 'SSSS'}],
        }, fixture['halls'])

    def test_a_kind_override_sets_the_seed_layout_only(self):
        self.run_main(kind_overrides={'kino-x/saal-2': 'F'})
        layout = json.loads(self.read(self.seed_path))['cinemas'][0]['halls'][1]['layout']
        self.assertEqual(['FFFF', 'FFFF'], [r['cells'] for r in layout['rows']])
        self.assertEqual('WSSW', json.loads(self.read(self.fixture_path))['halls']['kino-x/saal-2'][0]['kinds'])

    def test_a_kind_override_for_an_unknown_hall_fails(self):
        with self.assertRaisesRegex(AssertionError, 'unknown hall'):
            self.run_main(kind_overrides={'kino-y/saal-1': 'F'})

    def test_a_seed_hall_left_without_a_layout_fails_before_writing(self):
        self.seed['cinemas'][0]['halls'].append({'seedKey': 'kino-x/saal-3', 'name': 'Saal 3'})
        self.write_seed()
        before = self.read(self.seed_path)
        with self.assertRaisesRegex(AssertionError, 'halls without a layout'):
            self.run_main()
        self.assertEqual(before, self.read(self.seed_path))
        self.assertFalse(os.path.exists(self.fixture_path))


if __name__ == '__main__':
    unittest.main()
