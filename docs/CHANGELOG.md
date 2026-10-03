# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/). Versions start at 0.1.0 with the first beta.

## [Unreleased]
### Added
- Project documentation system: architecture, data model, API design, security/threat model, roadmap, ADRs 0001–0009.
- Backend foundation: Spring Boot 4.1.1 (Java 21 target), Flyway baseline, deny-by-default security, request ids,
  RFC 9457 error responses, Testcontainers test base, Dockerfile (non-root, layered).
- Frontend foundation: Next.js 16, Tailwind 4, shadcn/ui, API client with CSRF header + safe error parsing,
  compliance status badge, app shell, Vitest + Playwright.
- GitHub Actions CI workflow (backend verify; frontend lint/typecheck/test/build/e2e smoke).
