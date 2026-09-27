#!/usr/bin/env python3
"""Filesystem safety and publication recovery tests; no build tools are needed."""
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import unquote, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import export_distribution as exporter


class ExportDestinationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='azscript export safety ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()

    def package(self, name, marker):
        directory = self.root / name
        directory.mkdir()
        (directory / 'manifest.json').write_text(
            json.dumps({'format': exporter.FORMAT}), encoding='utf-8')
        (directory / 'payload').write_text(marker, encoding='utf-8')
        return directory

    def backups(self):
        return list(self.root.glob('.azscript-previous-*'))

    def test_bilingual_assets_have_relocatable_links_and_package_commands(self):
        package = self.root / 'relocated documentation'
        package.mkdir()
        exporter.copy_assets(package)
        for relative in ('README', 'THIRD_PARTY', 'docs/USAGE', 'docs/QUICKSTART',
                         'docs/LANGUAGE', 'docs/EXEC_FORMAT', 'docs/HINT_LINKING',
                         'docs/EXTERN_LIBRARY', 'stdlib/MATH', 'stdlib/CONTAINERS'):
            chinese = package / (relative + '.md')
            english = package / (relative + '.en.md')
            self.assertIn(f']({english.name})', chinese.read_text(encoding='utf-8'))
            self.assertIn(f']({chinese.name})', english.read_text(encoding='utf-8'))
        for document in package.rglob('*.md'):
            content = document.read_text(encoding='utf-8')
            # Operator declarations such as operator[](key) inside code are not links.
            content = re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', content, flags=re.M | re.S)
            content = re.sub(r'`[^`\n]*`', '', content)
            for target in re.findall(r'\]\(([^)]+)\)', content):
                link = urlsplit(target)
                if link.scheme or not link.path:
                    continue
                with self.subTest(document=document.relative_to(package), target=target):
                    self.assertTrue((document.parent / unquote(link.path)).exists())
        language = (package / 'docs/LANGUAGE.en.md').read_text(encoding='utf-8')
        self.assertIn('./compile.sh examples/parser-regressions.azs', language)
        self.assertNotIn('python3 tools/build_and_test.py', language)
        self.assertNotIn('](../docs/', language)
        for name in ('HINT_LINKING.md', 'HINT_LINKING.en.md'):
            hint = (package / 'docs' / name).read_text(encoding='utf-8')
            self.assertIn('](../examples/', hint)
            self.assertNotIn('](../compiler/examples/', hint)

    def test_nonempty_output_requires_force_even_for_a_previous_export(self):
        destination = self.package('existing', 'original')
        with self.assertRaisesRegex(RuntimeError, 'not empty'):
            exporter.check_destination(destination, False)
        self.assertEqual((destination / 'payload').read_text(), 'original')
        self.assertEqual(exporter.check_destination(destination, True), destination.resolve())

    def test_force_rejects_missing_malformed_and_nonobject_manifests(self):
        manifests = [None, '{', '[]', 'null', 'false', '123', '"text"', '{}',
                     '{"format":"AnotherDistribution"}']
        for index, manifest in enumerate(manifests):
            with self.subTest(manifest=manifest):
                destination = self.root / str(index)
                destination.mkdir()
                (destination / 'payload').write_text('original')
                if manifest is not None:
                    (destination / 'manifest.json').write_text(manifest)
                with self.assertRaisesRegex(RuntimeError, '--force only replaces'):
                    exporter.check_destination(destination, True)
                self.assertEqual((destination / 'payload').read_text(), 'original')

    def test_source_tree_and_ancestors_cannot_be_replaced(self):
        source = self.root / 'source'
        source.mkdir()
        with patch.object(exporter, 'ROOT', source):
            for path in (source, self.root):
                with self.subTest(path=path):
                    with self.assertRaisesRegex(RuntimeError, 'source tree'):
                        exporter.check_destination(path, True)

    def test_symlink_output_cannot_replace_its_target(self):
        destination = self.package('existing', 'original')
        alias = self.root / 'alias'
        try:
            alias.symlink_to(destination, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest('Directory symlinks are unavailable on this system')
        with self.assertRaisesRegex(RuntimeError, 'symbolic link'):
            exporter.check_destination(alias, True)
        self.assertEqual((destination / 'payload').read_text(), 'original')

    def test_existing_regular_file_cannot_be_replaced(self):
        destination = self.root / 'file'
        destination.write_text('original')
        with self.assertRaisesRegex(RuntimeError, 'not a directory'):
            exporter.check_destination(destination, True)
        self.assertEqual(destination.read_text(), 'original')

    def test_publish_rechecks_destination_before_moving_the_old_package(self):
        incoming = self.package('incoming', 'replacement')
        destination = self.root / 'existing'
        destination.mkdir()
        (destination / 'unrelated').write_text('keep')
        with self.assertRaisesRegex(RuntimeError, '--force only replaces'):
            exporter.publish(incoming, destination, True)
        self.assertEqual((destination / 'unrelated').read_text(), 'keep')
        self.assertEqual((incoming / 'payload').read_text(), 'replacement')
        self.assertEqual(self.backups(), [])

    def test_failed_publication_restores_the_old_package(self):
        incoming = self.package('incoming', 'replacement')
        destination = self.package('existing', 'original')
        original_rename = Path.rename
        failure = OSError('injected publication failure')

        def rename(path, target):
            if path == incoming:
                raise failure
            return original_rename(path, target)

        with patch.object(Path, 'rename', rename):
            with self.assertRaises(OSError) as raised:
                exporter.publish(incoming, destination, True)
        self.assertIs(raised.exception, failure)
        self.assertEqual((destination / 'payload').read_text(), 'original')
        self.assertEqual((incoming / 'payload').read_text(), 'replacement')
        self.assertEqual(self.backups(), [])

    def test_failed_restoration_preserves_the_backup_and_reports_its_location(self):
        incoming = self.package('incoming', 'replacement')
        destination = self.package('existing', 'original')
        original_rename = Path.rename

        def rename(path, target):
            if path == incoming:
                # Simulate another process occupying the destination between
                # its removal and publication, preventing the restore rename.
                destination.mkdir()
                (destination / 'concurrent-file').write_text('keep concurrent data')
                raise OSError('injected publication failure')
            return original_rename(path, target)

        with patch.object(Path, 'rename', rename):
            with self.assertRaisesRegex(RuntimeError, 'Previous export is preserved at') as raised:
                exporter.publish(incoming, destination, True)
        self.assertEqual(len(self.backups()), 1)
        previous = self.backups()[0] / 'package'
        self.assertEqual((previous / 'payload').read_text(), 'original')
        self.assertEqual(json.loads((previous / 'manifest.json').read_text())['format'], exporter.FORMAT)
        self.assertIn(str(previous), str(raised.exception))
        self.assertEqual((destination / 'concurrent-file').read_text(), 'keep concurrent data')
        self.assertEqual((incoming / 'payload').read_text(), 'replacement')

    def test_failure_moving_old_package_preserves_it_and_cleans_empty_backup(self):
        incoming = self.package('incoming', 'replacement')
        destination = self.package('existing', 'original')
        original_rename = Path.rename

        def rename(path, target):
            if path == destination:
                raise OSError('injected old-package rename failure')
            return original_rename(path, target)

        with patch.object(Path, 'rename', rename):
            with self.assertRaisesRegex(OSError, 'old-package rename failure'):
                exporter.publish(incoming, destination, True)
        self.assertEqual((destination / 'payload').read_text(), 'original')
        self.assertEqual((incoming / 'payload').read_text(), 'replacement')
        self.assertEqual(self.backups(), [])

    def test_successful_replace_installs_new_package_and_removes_backup(self):
        incoming = self.package('incoming', 'replacement')
        destination = self.package('existing', 'original')
        (destination / 'old-only').write_text('old content')
        exporter.publish(incoming, destination, True)
        self.assertEqual((destination / 'payload').read_text(), 'replacement')
        self.assertFalse((destination / 'old-only').exists())
        self.assertFalse(incoming.exists())
        self.assertEqual(self.backups(), [])

    def test_new_and_empty_destinations_do_not_require_force(self):
        for exists in (False, True):
            with self.subTest(exists=exists):
                incoming = self.package('incoming-' + str(exists), 'replacement')
                destination = self.root / ('destination-' + str(exists))
                if exists:
                    destination.mkdir()
                exporter.publish(incoming, destination, False)
                self.assertEqual((destination / 'payload').read_text(), 'replacement')
                self.assertFalse(incoming.exists())
                self.assertEqual(self.backups(), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
