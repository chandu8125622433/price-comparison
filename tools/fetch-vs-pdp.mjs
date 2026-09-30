import { writeFile } from "fs/promises";
import { pathToFileURL } from "url";

const BASE = "https://www.vijaysales.com";

async function fetchText(url) {
  const res = await fetch(url, {
    headers: {
      "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
      "Accept-Language": "en-IN,en;q=0.9",
      Accept: "text/html,application/xhtml+xml",
    },
    redirect: "follow",
  });
  const text = await res.text();
  return { status: res.status, text };
}

function extractPdpLinks(html) {
  const out = new Set();
  const re = /href="([^"]*\/p\/[^"]+)"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    let u = m[1];
    if (u.startsWith("/")) u = BASE + u;
    out.add(u);
  }
  return [...out];
}

// 1) Try category listing page
const catUrl =
  BASE + "/c/televisions/brand/buy-samsung-televisions";
let res = await fetchText(catUrl);
console.log(
  "CATEGORY_FETCH status=" +
    res.status +
    " bytes=" +
    res.text.length
);
let links = extractPdpLinks(res.text);

// 2) Search page fallback
if (links.length === 0) {
  const searchUrl =
    BASE + "/search?q=samsung+55+inch+smart+tv";
  res = await fetchText(searchUrl);
  console.log(
    "SEARCH_FETCH status=" +
      res.status +
      " bytes=" +
      res.text.length
  );
  links = extractPdpLinks(res.text);
}

await writeFile(
  "tools/vs-pdp-links.txt",
  links.join("\n") + (links.length ? "\n" : "")
);
console.log("PDP_LINKS_FOUND=" + links.length);
if (links.length) console.log("FIRST=" + links[0]);