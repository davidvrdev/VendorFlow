"use client";

import { z } from "zod";

// Zod's JIT probes `new Function("")`; the exception is swallowed but a strict CSP (no 'unsafe-eval') still reports a
// violation. `jitless` skips the probe; validation is marginally slower, irrelevant for form-sized data.
// Runs at module load in the browser, before any form validates.
z.config({ jitless: true });

export function ZodCspConfig() {
  return null;
}
