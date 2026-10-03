# ADR-0001: Modular monolith (one Spring Boot API + one Next.js app)

- Status: Accepted · Date: 2026-10-03

## Context
Early-stage B2B SaaS, small team (one owner + AI agents), 10–100 vendors per customer.

## Problem
How many deployable units and what internal structure?

## Options considered
1. Microservices (auth, vendors, documents, notifications…)
2. Modular monolith backend + separate frontend
3. Next.js full-stack only (no Spring)

## Decision
Option 2. One Spring Boot application organized **package-by-feature** with internal layers
(`api / application / domain / infrastructure`) only where they add value, plus one Next.js app.

## Consequences
+ One deploy, one database, transactional consistency, simple local dev and debugging.
+ Feature packages give clear seams if a module ever needs extraction.
− Discipline required: no cross-feature repository access (enforced by review, later by an architecture test).

## Alternatives rejected
- Microservices: distributed transactions, ops cost and latency with zero benefit at our scale.
- Next.js only: the stack decision mandates Java/Spring for the backend; also keeps domain logic in a strongly typed,
  well-tested backend independent of the UI framework.
