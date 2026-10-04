import { test as base, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";

/**
 * Shared `test` for every spec: fails a test on any Content-Security-Policy violation or uncaught page error, in ANY
 * page of ANY context (specs open extra contexts with `browser.newContext()` for second users, so the guard hooks
 * `newContext` too). Without it CSP regressions on authenticated pages (dialogs, menus, uploads) pass silently, because
 * the browser only logs them.
 */
const CSP_CONSOLE = /content security policy|refused to (execute|apply|load|connect|frame)/i;

function watch(context: BrowserContext, problems: string[]) {
  // The DOM event carries the directive and blocked URI; it is mirrored to the console to catch it with one listener.
  void context.addInitScript(() => {
    document.addEventListener("securitypolicyviolation", (e) => {
      console.error(`Content Security Policy violation: ${e.violatedDirective} blocked=${e.blockedURI}`);
    });
  });
  const attach = (page: Page) => {
    page.on("console", (msg) => {
      if (CSP_CONSOLE.test(msg.text())) problems.push(`[${page.url()}] ${msg.text()}`);
    });
    page.on("pageerror", (error) => problems.push(`[${page.url()}] uncaught: ${error.message}`));
  };
  context.pages().forEach(attach);
  context.on("page", attach);
}

export const test = base.extend<{ pageGuard: void }>({
  pageGuard: [
    async ({ browser, context }, use) => {
      const problems: string[] = [];
      watch(context, problems);
      const original = (browser as Browser).newContext.bind(browser);
      (browser as Browser).newContext = (async (...args: Parameters<Browser["newContext"]>) => {
        const created = await original(...args);
        watch(created, problems);
        return created;
      }) as Browser["newContext"];
      await use();
      (browser as Browser).newContext = original as Browser["newContext"];
      expect(problems, "CSP violations or uncaught page errors").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
export type { Page, Browser, BrowserContext } from "@playwright/test";
