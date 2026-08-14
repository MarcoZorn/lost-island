import unittest

from lostisland import config


class ConfigTest(unittest.TestCase):
    def test_merge_keeps_defaults_for_missing_keys(self):
        merged = config._merge(config.DEFAULTS, {"margin_top": 20})
        self.assertEqual(merged["margin_top"], 20)
        self.assertEqual(merged["layer"], config.DEFAULTS["layer"])

    def test_merge_is_deep_for_modules(self):
        merged = config._merge(config.DEFAULTS, {"modules": {"music": False}})
        self.assertFalse(merged["modules"]["music"])
        self.assertTrue(merged["modules"]["battery"])

    def test_defaults_survive_broken_config(self):
        self.assertEqual(config._merge(config.DEFAULTS, {}), config.DEFAULTS)


if __name__ == "__main__":
    unittest.main()
