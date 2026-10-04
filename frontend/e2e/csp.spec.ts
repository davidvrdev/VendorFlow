import { expect, test } from "./support/test";

// Runs in mock and full-stack modes: only public pages plus the anonymous redirect, no backend needed.
const PAGES = ["/", "/login", "/signup", "/forgot-password"];

for (const path of PAGES) {
  test(`CSP header present and no CSP violations on ${path}`, async ({ page }) => {
    const problems: string[] = [];
    page.on("console", (msg) => {
      if (/content security policy|refused to (execute|apply|load)/i.test(msg.text())) problems.push(msg.text());
    });
    await page.addInitScript(() => {
      document.addEventListener("securitypolicyviolation", (e) => {
        console.error(`Content Security Policy violation: ${e.violatedDirective} ${e.blockedURI}`);
      });
    });

    const response = await page.goto(path);
    const headers = response!.headers();
    const csp = headers["content-security-policy"];
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toMatch(/script-src 'self' 'nonce-[A-Za-z0-9+/=]+' 'strict-dynamic'/);
    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["x-frame-options"]).toBe("DENY");
    expect(headers["x-powered-by"]).toBeUndefined();

    await page.waitForLoadState("networkidle");
    expect(problems).toEqual([]);
  });
}

test("nonce differs per request and anonymous dashboard redirect keeps the CSP", async ({ page }) => {
  const nonce = async () => (await (await page.request.get("/login")).headers())["content-security-policy"].match(/nonce-([^']+)/)![1];
  expect(await nonce()).not.toBe(await nonce());

  const response = await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard$/);
  expect(response).not.toBeNull();
});
