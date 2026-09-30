import { readFile, writeFile } from "fs/promises";

const html = await readFile("tools/vs-pdp-lg32.html", "utf8");

// Find rich contexts that likely encode the retail/selling price.
const needles = ["₹86,990", "879.00", "EMI", "sp-offers", "selling", "Selling", "price", "finalPrice", "offerPrice"];
const out = [];
for (const n of needles.slice(0, 3)) {
  let idx = html.indexOf(n);
  if (idx < 0) continue;
  const start = Math.max(0, idx - 400);
  const end = Math.min(html.length, idx + 200);
  const snippet = html.slice(start, end);
  out.push(`--${n} @ ${idx}--`);
  out.push(snippet.replace(/\s+/g, " ").slice(0, 600));
  out.push("");
}
// generic price attr scan
const attrRe = /<[^>]{0,200}\b(sp-price|actual-price|final-price|price|selling|offer)[^>]{0,100}data-[^>]{0,200}>/gi;
const attrs = [];
let am;
let guard = 0;
while ((am = attrRe.exec(html)) !== null && attrs.length < 5 && guard++ < 2000) {
  const s = am[0].replace(/\s+/g, " ");
  if (/₹|[\d,]{3,}/.test(s)) attrs.push(s.slice(0, 220));
}
if (attrs.length) {
  out.push("--ATTRS--");
  out.push(...attrs);
}
await writeFile("tools/vs-pdp-snippets.txt", out.join("\n"));
console.log("wrote tools/vs-pdp-snippets.txt len=" + out.join("\n").length);