"""Publication regressions for archive completeness, independent of APK inputs."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from package_release import source_archive_entries, verify_archive_sources


class SourcePackagingTests(unittest.TestCase):
    def test_internal_header_alias_is_included_and_matches_manifest(self):
        with tempfile.TemporaryDirectory(prefix='bender-package-test-') as directory:
            root = Path(directory)
            header = root / 'header.h'
            header.write_bytes(b'/* build input */\n')
            alias = root / 'include.h'
            alias.symlink_to(header)
            entries = list(source_archive_entries(root))
            self.assertEqual({name.as_posix() for _, name in entries},
                             {'bender-wifi-lab/header.h', 'bender-wifi-lab/include.h'})
            archive = root / 'source.zip'
            with zipfile.ZipFile(archive, 'w') as target:
                for source, name in entries:
                    target.write(source, name)
            verify_archive_sources(archive, {'sha256': {'include.h': hashlib.sha256(header.read_bytes()).hexdigest()}})

    def test_external_file_alias_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='bender-package-test-') as directory:
            parent = Path(directory)
            root = parent / 'project'
            root.mkdir()
            outside = parent / 'private.txt'
            outside.write_text('must not package')
            (root / 'alias.txt').symlink_to(outside)
            with self.assertRaisesRegex(ValueError, 'escapes project'):
                list(source_archive_entries(root))

    def test_missing_manifest_input_cannot_pass(self):
        with tempfile.TemporaryDirectory(prefix='bender-package-test-') as directory:
            archive = Path(directory) / 'source.zip'
            with zipfile.ZipFile(archive, 'w') as target:
                target.writestr('bender-wifi-lab/unrelated.txt', 'x')
            with self.assertRaisesRegex(AssertionError, 'missing build input'):
                verify_archive_sources(archive, {'sha256': {'required.h': '0' * 64}})

    def test_directory_alias_preserves_include_path(self):
        with tempfile.TemporaryDirectory(prefix='bender-package-test-') as directory:
            root = Path(directory)
            (root / 'src').mkdir()
            (root / 'src/header.h').write_text('header')
            (root / 'include').symlink_to(root / 'src', target_is_directory=True)
            names = {name.as_posix() for _, name in source_archive_entries(root)}
            self.assertEqual(names, {'bender-wifi-lab/src/header.h', 'bender-wifi-lab/include/header.h'})

    def test_directory_alias_cycle_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='bender-package-test-') as directory:
            root = Path(directory)
            (root / 'cycle').symlink_to(root, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'alias cycle'):
                list(source_archive_entries(root))


if __name__ == '__main__':
    unittest.main()
