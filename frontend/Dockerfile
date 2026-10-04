# syntax=docker/dockerfile:1
# Next.js 16 standalone image (ADR-0010). Three stages so the runtime image has no node_modules/dev tooling.

# ---- deps: exact lockfile install (cached until package*.json change) ----
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# ---- build ----
FROM node:24-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
# IMPORTANT: next.config.ts bakes BACKEND_URL into the /api/* rewrite at BUILD time (routes-manifest). Changing the
# backend address therefore needs a rebuild. The default is the Render private hostname of the API service
# (render.yaml: service "vendorflow-api", port 8080). Pass --build-arg BACKEND_URL=... for other topologies.
ARG BACKEND_URL=http://vendorflow-api:8080
ENV BACKEND_URL=${BACKEND_URL}
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---- runtime: non-root, standalone server only ----
FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
# The official node image ships a non-root "node" user (uid 1000).
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3000
# busybox wget is in alpine; /healthz does not touch the backend.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/healthz || exit 1
CMD ["node", "server.js"]
