"""Synthetic importer fixtures, not a shipped card catalog."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('importer', pathlib.Path(__file__).parents[1] / 'import_arkhamdb.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ImportTest(unittest.TestCase):
    def test_metadata_only_and_hidden_cards_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'pack').mkdir()
            (root / 'packs.json').write_text(json.dumps([dict(code='p', name='Test product')]))
            (root / 'pack/test.json').write_text(json.dumps([
                dict(code='1', name='Test title', pack_code='p', position=1, text='Do not include', quantity=3, imagesrc='Do not include'),
                dict(code='hidden', name='Hidden pseudo-card', pack_code='p', hidden=True)]))
            result = module.convert(root)
            self.assertEqual(1, len(result['cards']))
            card = result['cards'][0]
            self.assertEqual('arkhamdb:1', card['id'])
            self.assertFalse(set(card) & {'text', 'quantity', 'imagesrc'})
            self.assertFalse(result['retailManifestsVerified'])

    def test_conflicting_ids_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'pack').mkdir()
            (root / 'packs.json').write_text(json.dumps([dict(code='p', name='Test product')]))
            (root / 'pack/test.json').write_text(json.dumps([
                dict(code='1', name='A', pack_code='p'), dict(code='1', name='B', pack_code='p')]))
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                module.convert(root)

if __name__ == '__main__':
    unittest.main()
