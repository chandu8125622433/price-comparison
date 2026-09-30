import re
import sys
from pathlib import Path

def extract(file_path: str, out_path: str) -> None:
    data = Path(file_path).read_text(encoding="utf-8", errors="ignore")

    # Look for Vijay Sales product-page hrefs (absolute and relative).
    patterns = [
        r'href="(https://www\.vijaysales\.com/p/[^"]+)"',
        r'href="(/p/[^"]+)"',
        r'"url"\s*:\s*"(https://www\.vijaysales\.com/p/[^"]+)"',
        r'"url"\s*:\s*"(/p/[^"]+)"',
    ]

    found: dict[str, bool] = {}
    for pat in patterns:
        for m in re.finditer(pat, data):
            raw = m.group(1)
            if raw.startswith("/p/"):
                raw = "https://www.vijaysales.com" + raw
            found[raw] = True

    # Also try JSON-LD product nodes with name + sku + price for richer targets.
    ld_blocks = re.findall(r'<script type="application/ld\+json">(.*?)</script>', data, re.DOTALL)
    for block in ld_blocks:
        if '"@type"' in block and '"Product"' in block:
            name_m = re.search(r'"name"\s*:\s*"([^"]+)"', block)
            url_m = re.search(r'"url"\s*:\s*"(https?://[^"]+)"', block)
            price_m = re.search(r'"price"\s*:\s*"?([\d.,]+)"?', block)
            if url_m:
                found[url_m.group(1)] = True

    links = sorted(found.keys())
    Path(out_path).write_text("\n".join(links) + ("\n" if links else ""), encoding="utf-8")
    print(f"{file_path}: extracted {len(links)} unique product links -> {out_path}")

if __name__ == "__main__":
    extract(sys.argv[1], sys.argv[2])