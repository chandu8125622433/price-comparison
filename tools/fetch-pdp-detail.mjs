import { writeFile, readFile } from "fs/promises";

const BASE = "https://www.vijaysales.com";
const url =
  "https://www.vijaysales.com/p/238476/lg-80-cm-32-inch-hd-ai-smart-webos-led-tv-hdr10-pro-a5-gen6-ai-processor-2-0-channel-ai-sound-32lr605b6lc";

const res = await fetch(url, {
  headers: {
    "User-Agent":
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept-Language": "en-IN,en;q=0.9",
    Accept: "text/html,application/xhtml+xml",
  },
  redirect: "follow",
});
const html = await res.text();
await writeFile("tools/vs-pdp-lg32.html", html);

const title =
  (html.match(/<title[^>]*>([^<]+)<\/title>/i) || [])[1] || "";
const jsonLd = html.match(
  /<script type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi
) || [];

const priceHits = [];
const rePrice =
  /data-\w*price="?([\d,\.]+)"?|"price"\s*:\s*"?([\d,\.]+)"?|₹\s*([\d,\.]+)/gi;
let m;
while ((m = rePrice.exec(html)) !== null && priceHits.length < 6) {
  priceHits.push(m[0].slice(0, 80));
}

const facts =
  "URL=" +
  url +
  "\nSTATUS=" +
  res.status +
  "\nBYTES=" +
  html.length +
  "\nTITLE=" +
  title.slice(0, 200) +
  "\nLD_BLOCKS=" +
  jsonLd.length +
  "\nPRICE_HITS=" +
  JSON.stringify(priceHits) +
  "\n";
await writeFile("tools/vs-pdp-facts.txt", facts);