# ADR-0009: Runtime versions — Java 21 target, Spring Boot 4.1, Next.js 16

- Status: Accepted · Date: 2026-10-03

## Context
The dev machine has JDK 25 (no JDK 21). The spec says Java 21. Current stable (verified 2026-10-03 on
start.spring.io and npm): Spring Boot 4.1.1, Next.js 16.3, React 19.3, Tailwind CSS 4.3.

## Decision
- Compile with `maven.compiler.release=21` (bytecode + API level 21). Builds on any JDK ≥ 21 (local JDK 25 is fine).
  CI and the production container use Temurin 21. Moving the baseline to Java 25 LTS is a separate future decision.
- Spring Boot 4.1.x. Maven via the Maven Wrapper (no global install).
- Next.js 16 App Router, React 19, Tailwind CSS 4, shadcn/ui, TypeScript strict.

## Consequences
+ `release=21` prevents accidental use of post-21 APIs.
− Spring Boot 4 / Spring Security 7 changed several APIs from Boot 3 — always check official docs, never assume.
