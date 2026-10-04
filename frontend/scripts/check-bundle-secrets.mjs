// Run after `next build`: fails if secret-looking strings or server-only env var names end up in files that are
// shipped to the browser (.next/static). Only NEXT_PUBLIC_* values may be inlined by Next.
import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

const root = path.join(process.cwd(), ".next", "static");
const PATTERNS = [
  [/sk_(live|test)_[A-Za-z0-9]{8,}/, "Stripe secret key"],
  [/rk_(live|test)_[A-Za-z0-9]{8,}/, "Stripe restricted key"],
  [/whsec_[A-Za-z0-9]{8,}/, "Stripe webhook secret"],
  [/re_[A-Za-z0-9]{20,}/, "Resend API key"],
  [/STRIPE_(SECRET_KEY|WEBHOOK_SECRET)/, "Stripe env var name"],
  [/RESEND_API_KEY/, "Resend env var name"],
  [/APP_IP_HASH_SECRET|DATABASE_PASSWORD|STORAGE_S3_/, "backend env var name"],
  [/BACKEND_URL/, "server-only BACKEND_URL"],
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----/, "private key"],
];

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) yield* walk(full);
    else if (/\.(js|css|map|json|html|txt)$/.test(name)) yield full;
  }
}

let dirExists = true;
try { statSync(root); } catch { dirExists = false; }
if (!dirExists) {
  console.error("No .next/static found. Run `npm run build` first.");
  process.exit(1);
}

const findings = [];
let scanned = 0;
for (const file of walk(root)) {
  scanned++;
  const text = readFileSync(file, "utf8");
  for (const [re, label] of PATTERNS) if (re.test(text)) findings.push(`${path.relative(process.cwd(), file)}: ${label}`);
}
if (findings.length) {
  console.error("Possible secrets in client bundle:\n" + findings.join("\n"));
  process.exit(1);
}
console.log(`check-bundle-secrets: ${scanned} client files scanned, no secrets found.`);
