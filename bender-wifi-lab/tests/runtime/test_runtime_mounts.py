"""Private bind-mount recovery in a mocked filesystem; no real mounts or radio."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

GIT_BASH=Path('C:/Program Files/Git/bin/bash.exe')
BASH=str(GIT_BASH) if os.name=='nt' and GIT_BASH.exists() else shutil.which('bash')
SOURCE=Path(__file__).resolve().parents[2]/'runtime/runtime-root.sh'


def shell_path(path):
    value=Path(path).resolve().as_posix()
    return '/'+value[0].lower()+value[2:] if os.name=='nt' else value


MOCKS=r'''
stat(){
    case "$2" in
        %u) echo 0;;
        %d:%i)
            name=$(basename "$3")
            if [ "${MOCK_UNKNOWN:-}" = "$name" ] && [ "$3" != "/$name" ]; then echo unknown;else echo "host:$name";fi;;
        *) command stat "$@";;
    esac
}
mount(){
    target=${@: -1}
    printf '0 0 0:0 / %s rw - fixture fixture rw\n' "$target" >> "$MOCK_ROOT/mounts"
    echo "mount $target" >> "$MOCK_ROOT/calls"
}
umount(){
    echo "umount $1" >> "$MOCK_ROOT/calls"
    [ "${MOCK_BUSY:-}" != "$(basename "$1")" ] || return 1
    awk -v p="$1" '$5!=p' "$MOCK_ROOT/mounts" > "$MOCK_ROOT/mounts.next"
    mv "$MOCK_ROOT/mounts.next" "$MOCK_ROOT/mounts"
}
'''


@unittest.skipUnless(BASH,'Bash required for mount control-flow tests')
class RuntimeMountTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='bender-mounts-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.posix=shell_path(self.root)
        for name in ('proc','sys','dev'):(self.root/'runtime'/name).mkdir(parents=True)
        (self.root/'runtime/.bender-ready').write_text('pinned image')
        (self.root/'mounts').write_text('')
        source=SOURCE.read_text().replace('/data/local/bender-wifi/runtime-arm64-v1',self.posix+'/runtime').replace('/proc/self/mountinfo',self.posix+'/mounts')
        self.source=source

    def inherited(self,*parts):
        (self.root/'mounts').write_text(''.join(f'0 0 0:0 / {self.posix}/runtime/{part} rw - fixture fixture rw\n' for part in parts))

    def run_mounts(self,**env):
        script=self.root/'run.sh'
        script.write_text('#!/bin/bash\n'+MOCKS+self.source+'\nruntime_mounts\nsetup=$?\nruntime_unmounts\ncleanup=$?\nprintf "setup=%s cleanup=%s retained=%s\\n" "$setup" "$cleanup" "$BENDER_MOUNTED"\n[ "$setup" = 0 ] && [ "$cleanup" = 0 ]\n',newline='\n')
        result=subprocess.run([BASH,shell_path(script)],capture_output=True,text=True,timeout=10,env={**os.environ,'MOCK_ROOT':self.posix,**env})
        self.output=result.stdout+result.stderr
        return result.returncode

    def test_new_mounts_are_released(self):
        self.assertEqual(0,self.run_mounts(),self.output)
        self.assertEqual('',(self.root/'mounts').read_text())

    def test_verified_orphan_mounts_are_adopted_and_released(self):
        self.inherited('proc','sys','dev')
        self.assertEqual(0,self.run_mounts(),self.output)
        calls=(self.root/'calls').read_text().splitlines()
        self.assertTrue(all(x.startswith('umount ') for x in calls))
        self.assertEqual(3,len(calls));self.assertEqual('',(self.root/'mounts').read_text())

    def test_unknown_mount_is_preserved_and_blocks_runtime(self):
        self.inherited('proc','sys')
        self.assertNotEqual(0,self.run_mounts(MOCK_UNKNOWN='sys'),self.output)
        self.assertIn('/runtime/sys ',(self.root/'mounts').read_text())
        self.assertNotIn('umount '+self.posix+'/runtime/sys',(self.root/'calls').read_text())

    def test_busy_mount_is_retained_and_next_job_recovers_it(self):
        self.assertNotEqual(0,self.run_mounts(MOCK_BUSY='dev'),self.output)
        self.assertIn('retained= dev',self.output)
        self.assertIn('/runtime/dev ',(self.root/'mounts').read_text())
        self.assertEqual(0,self.run_mounts(),self.output)
        self.assertEqual('',(self.root/'mounts').read_text())


if __name__=='__main__':unittest.main()
