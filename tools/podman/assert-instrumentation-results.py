#!/usr/bin/env python3
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    files = list(args.results.rglob("*.xml"))
    if not files:
        raise ValueError("instrumentation produced no XML results")
    tests = failures = errors = skipped = 0
    locus_test = False
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.attrib.get("tests", 0))
        failures += int(root.attrib.get("failures", 0))
        errors += int(root.attrib.get("errors", 0))
        skipped += int(root.attrib.get("skipped", 0))
        for case in root.iter("testcase"):
            if case.attrib.get("classname", "").endswith("LocusEndToEndTest"):
                locus_test = True
                if case.find("skipped") is not None:
                    raise ValueError("the Locus integration test was skipped")
    if tests == 0 or failures or errors or skipped:
        raise ValueError(
            f"instrumentation was incomplete: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}"
        )
    if not locus_test:
        raise ValueError("the Locus integration test did not run")
    print(f"Verified {tests} instrumentation tests with no failures or skips.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
