#!/usr/bin/env node
// Key-less dependency vulnerability gate for the backend (no NVD API key needed).
//   1. resolves the runtime dependency set with the Maven wrapper,
//   2. asks the public OSV database (api.osv.dev, GitHub advisories + NVD + ecosystem feeds) about each coordinate,
//   3. prints a table and exits 1 if any finding is HIGH or CRITICAL (MODERATE/LOW are listed, not fatal).
// Only public Maven coordinates leave the machine. Run from backend/: node scripts/osv-scan.mjs
import { execFileSync } from "node:child_process";
import { readFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const isWindows = process.platform === "win32";
const dir = mkdtempSync(join(tmpdir(), "osv-"));
const out = join(dir, "deps.txt");
execFileSync(isWindows ? resolve("mvnw.cmd") : "./mvnw", ["-q", "-B", "dependency:list", "-DincludeScope=runtime",
  `-DoutputFile=${out}`], { stdio: "inherit", shell: isWindows });

const coords = readFileSync(out, "utf8").split("\n")
  .map((l) => l.trim().replace(/ --.*$/, "").split(":"))
  .filter((p) => p.length >= 5 && p[2] === "jar")
  .map((p) => ({ name: `${p[0]}:${p[1]}`, version: p[3] }));

const post = async (url, body) => {
  const res = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.json();
};

const batch = await post("https://api.osv.dev/v1/querybatch", {
  queries: coords.map((c) => ({ package: { ecosystem: "Maven", name: c.name }, version: c.version })),
});

const findings = [];
for (let i = 0; i < coords.length; i++) {
  for (const v of batch.results[i]?.vulns ?? []) {
    const res = await fetch(`https://api.osv.dev/v1/vulns/${v.id}`);
    const d = res.ok ? await res.json() : {};
    findings.push({
      pkg: `${coords[i].name}:${coords[i].version}`, id: v.id,
      severity: String(d.database_specific?.severity ?? "UNKNOWN").toUpperCase(),
      cve: (d.aliases ?? []).filter((a) => a.startsWith("CVE")).join(","), summary: (d.summary ?? "").slice(0, 100),
    });
  }
}

console.log(`Scanned ${coords.length} runtime dependencies.`);
for (const f of findings) console.log(`${f.severity.padEnd(9)} ${f.pkg}  ${f.id} ${f.cve}  ${f.summary}`);
const fatal = findings.filter((f) => f.severity === "HIGH" || f.severity === "CRITICAL" || f.severity === "UNKNOWN");
if (fatal.length > 0) {
  console.error(`FAIL: ${fatal.length} high/critical/unknown-severity finding(s). Upgrade or document in docs/SECURITY.md.`);
  process.exit(1);
}
console.log(findings.length === 0 ? "OK: no known vulnerabilities." : "OK: only moderate/low findings (see above).");
