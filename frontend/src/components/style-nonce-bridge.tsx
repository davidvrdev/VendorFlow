"use client";

// Radix (via react-remove-scroll-bar / style-singleton) injects a <style> element when a dialog, select or menu locks
// page scroll. The library reads the nonce for it from `__webpack_nonce__`, which Next/Turbopack does not set, so the
// strict style-src CSP blocked it (found by the e2e CSP fixture). The server nonce is exposed on the property of the
// scripts Next renders (the attribute itself is hidden by browsers), so copy it over once at module load.
declare global {
  interface Window {
    __webpack_nonce__?: string;
  }
}

if (typeof document !== "undefined") {
  const nonce = document.querySelector<HTMLScriptElement>("script[nonce]")?.nonce;
  if (nonce) window.__webpack_nonce__ = nonce;
}

export function StyleNonceBridge() {
  return null;
}
