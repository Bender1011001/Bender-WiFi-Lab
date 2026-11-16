#!/usr/bin/env python3
"""Supervisor-only automatic preparation/cleanup; never submits radio frames."""
import json
from pathlib import Path
import sys
import privacy_profile


def main():
    operation, name = sys.argv[1:]
    job = Path(name)
    if operation == 'session_prepare':
        request = json.loads((job / 'request.json').read_text())
        profile = request.get('privacy_profile', 'wifi')
        if profile not in ('wifi', 'strict', 'wps'):
            raise ValueError('unknown privacy profile')
        report = privacy_profile.prepare(job, profile=profile, session=True)
        report.update(profile=profile, automatic=True,
                      scope='wlan0_wlan1_ip_stack' if profile == 'wps' else 'wlan0_ip_stack' if profile == 'wifi' else 'all_ip_stack_and_radio_settings')
        (job / 'privacy-state.json').write_text(json.dumps(report, indent=2))
        (job / 'privacy-preparation.json').write_text(json.dumps(report, indent=2))
        return 0 if report['state'] == 'prepared' else 1
    if operation == 'session_restore':
        report = privacy_profile.restore(job, owner_only=True)
        return 0 if report['state'] in ('restored', 'idle') else 1
    raise ValueError('unsupported session operation')


if __name__ == '__main__':
    sys.exit(main())
