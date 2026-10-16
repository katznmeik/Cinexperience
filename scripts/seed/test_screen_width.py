"""Tests for the seed generator's screen width (yorck_seatplan_to_layout.screen_width).

Standard library only, no crawl data. From the repository root (-B: no __pycache__):
  python3 -I -B -m unittest discover -s scripts/seed -p 'test_*.py'
or: python3 -I -B scripts/seed/test_screen_width.py
"""
import importlib.util
import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SEED = os.path.join(HERE, '..', '..', 'core', 'data', 'src', 'main', 'assets', 'seed', 'yorck.json')


def load_tool():
    """The generator, loaded from its file next to this one (no sys.path needed)."""
    spec = importlib.util.spec_from_file_location('yorck_seatplan_to_layout', os.path.join(HERE, 'yorck_seatplan_to_layout.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


tool = load_tool()


def hall(screen_width=None, columns=None, key='kino/saal-1'):
    """A seed hall entry with an optional screenWidth and an old layout `columns` wide."""
    h = {'seedKey': key, 'name': 'Saal 1'}
    if screen_width is not None:
        h['screenWidth'] = screen_width
    if columns is not None:
        h['layout'] = {'formatVersion': 1, 'rows': [{'label': '1', 'cells': 'S' * columns},
                                                    {'cells': '.' * columns},
                                                    {'label': '2', 'cells': 'S' * columns}]}
    return h


class ScreenWidthTest(unittest.TestCase):

    def test_a_hall_without_a_screen_gets_the_grid_width(self):
        self.assertEqual((12.0, None), tool.screen_width(hall(), 12))
        self.assertIsInstance(tool.screen_width(hall(), 12)[0], float)
        self.assertEqual((12.0, None), tool.screen_width(hall(columns=10), 12))

    def test_a_screen_as_wide_as_the_old_grid_follows_the_new_grid(self):
        self.assertEqual((18.0, None), tool.screen_width(hall(17.0, columns=17), 18))
        self.assertEqual((16.0, None), tool.screen_width(hall(17.0, columns=17), 16))
        self.assertEqual((17.0, None), tool.screen_width(hall(17.0, columns=17), 17))

    def test_any_other_screen_is_kept(self):
        # E.g. set by hand.
        self.assertEqual((14.0, None), tool.screen_width(hall(14.0, columns=16), 16))
        # A changed grid keeps it too, but says so.
        width, warning = tool.screen_width(hall(14.0, columns=16), 17)
        self.assertEqual(14.0, width)
        self.assertIn('16 to 17 columns', warning)
        # Without an old layout there is nothing to compare with.
        self.assertEqual((9.0, None), tool.screen_width(hall(9.0), 12))

    def test_a_measured_screen_is_kept_even_as_wide_as_the_grid(self):
        # delphi LUX Saal 2: 14 seats measured from the Yorck app, on 14 columns.
        lux = hall(14.0, columns=14, key='delphi-lux/saal-2')
        self.assertEqual((14.0, None), tool.screen_width(lux, 14))
        width, warning = tool.screen_width(lux, 15)
        self.assertEqual(14.0, width)
        self.assertIn('14 to 15 columns', warning)

    def test_the_current_seed_regenerated_at_the_same_widths_is_unchanged(self):
        with open(SEED, encoding='utf-8') as f:
            seed = json.load(f)
        halls = [h for c in seed['cinemas'] for h in c['halls']]
        self.assertTrue(halls)
        for h in halls:
            columns = len(h['layout']['rows'][0]['cells'])
            with self.subTest(h['seedKey']):
                self.assertEqual((h['screenWidth'], None), tool.screen_width(h, columns))
                width, warning = tool.screen_width(h, columns + 1)
                if h['seedKey'].startswith('delphi-lux/'):
                    # Measured (YorckSeedTest.screens pins them): kept, with a warning.
                    self.assertEqual(h['screenWidth'], width)
                    self.assertIsNotNone(warning)
                else:
                    # Every other screen is as wide as the grid (YorckSeedTest.screens), so it follows a new width.
                    self.assertEqual((columns + 1.0, None), (width, warning))


if __name__ == '__main__':
    unittest.main()
