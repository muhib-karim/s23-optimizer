"""Print a Markdown coverage table from a Kover/JaCoCo XML report (stdlib only)."""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError) as error:
    print(f"Coverage report unavailable: {error}")
    sys.exit(0)


def pct(node, kind):
    for counter in node.findall("counter"):
        if counter.get("type") == kind:
            missed, covered = int(counter.get("missed")), int(counter.get("covered"))
            return 100.0 * covered / (missed + covered) if missed + covered else None
    return None


print("### Kover coverage (debug unit + Robolectric tests)\n")
print("| Package | Line % | Branch % |")
print("|---|---:|---:|")
for package in sorted(root.findall("package"), key=lambda p: p.get("name")):
    line, branch = pct(package, "LINE"), pct(package, "BRANCH")
    print(f"| `{package.get('name').replace('/', '.')}` | {line:.1f} | {'' if branch is None else f'{branch:.1f}'} |" if line is not None else "")
total = pct(root, "LINE")
print(f"\n**Total line coverage: {total:.1f}%**" if total is not None else "\nNo line counters found.")
