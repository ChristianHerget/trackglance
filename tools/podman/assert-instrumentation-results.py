#!/usr/bin/env python3
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def synthetic_cases(source):
    expected = set()
    for path in source.rglob("*.kt"):
        if path.name == "LocusEndToEndTest.kt":
            continue
        text = path.read_text()
        package = re.search(r"(?m)^package ([\w.]+)", text)
        for method in re.findall(r"(?m)^\s*@Test\b[\s\S]*?\bfun\s+(\w+)\s*\(", text):
            expected.add((f"{package[1]}.{path.stem}", method))
    if not expected:
        raise ValueError("no expected synthetic test cases found")
    return expected


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("--synthetic", action="store_true")
    args = parser.parse_args()
    files = list(args.results.rglob("*.xml"))
    if not files:
        raise ValueError("instrumentation produced no XML results")
    tests = failures = errors = skipped = 0
    locus_test = False
    observed = set()
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.attrib.get("tests", 0))
        failures += int(root.attrib.get("failures", 0))
        errors += int(root.attrib.get("errors", 0))
        skipped += int(root.attrib.get("skipped", 0))
        for case in root.iter("testcase"):
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError("instrumentation test did not complete successfully")
            identity = (case.attrib.get("classname", ""), case.attrib.get("name", ""))
            if identity in observed:
                raise ValueError(f"duplicate instrumentation test: {identity}")
            observed.add(identity)
            if case.attrib.get("classname", "").endswith("LocusEndToEndTest"):
                locus_test = True
                if case.find("skipped") is not None:
                    raise ValueError("the Locus integration test was skipped")
    if tests == 0 or failures or errors or skipped:
        raise ValueError(
            f"instrumentation was incomplete: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}"
        )
    if args.synthetic:
        if locus_test:
            raise ValueError("real Locus tests must be excluded from the synthetic suite")
        source = Path(__file__).resolve().parents[2] / "android/app/src/androidTest"
        expected = synthetic_cases(source)
        if observed != expected:
            raise ValueError(f"synthetic test coverage differs: missing={sorted(expected - observed)}, unexpected={sorted(observed - expected)}")
    elif not locus_test:
        raise ValueError("the Locus integration test did not run")
    print(f"Verified {tests} instrumentation tests with no failures or skips.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
