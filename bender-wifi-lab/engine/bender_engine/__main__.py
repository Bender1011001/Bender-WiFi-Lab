import argparse
import json
import os
from pathlib import Path
import sys
import time

from .analysis import AnalysisCancelled, MAX_SECONDS, analyze_pcap, check_budget


def main():
    parser = argparse.ArgumentParser(description="Offline Wi-Fi observations; no active radio operations")
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("analyze")
    command.add_argument("--pcap", required=True)
    command.add_argument("--output", required=True)
    command.add_argument("--tshark", default="tshark")
    command.add_argument("--cancel-file", help="Cancel when this file exists; no partial output is published")
    args = parser.parse_args()
    deadline = time.monotonic() + MAX_SECONDS
    temporary = None
    try:
        if Path(args.pcap).resolve() == Path(args.output).resolve():
            raise ValueError("Capture and output must have different paths")
        result = analyze_pcap(args.pcap, args.tshark, deadline=deadline, cancel_file=args.cancel_file)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_name(output.name + ".tmp")
        with temporary.open("w", encoding="utf-8") as stream:
            for index, chunk in enumerate(json.JSONEncoder(ensure_ascii=True, indent=2).iterencode(result)):
                if index % 256 == 0:
                    check_budget(deadline, args.cancel_file)
                stream.write(chunk)
        check_budget(deadline, args.cancel_file)
        os.replace(temporary, output)
    except AnalysisCancelled as error:
        print(str(error), file=sys.stderr)
        return 130
    except (OSError, ValueError, RuntimeError) as error:
        print("Analysis failed: " + str(error), file=sys.stderr)
        return 2
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
