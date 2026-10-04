// Liveness probe for the platform (Render health check / Docker HEALTHCHECK). Deliberately does NOT call the backend:
// a backend outage must not make the platform restart the frontend in a loop.
export const dynamic = "force-dynamic";

export function GET() {
  return new Response("ok", { status: 200, headers: { "Cache-Control": "no-store", "Content-Type": "text/plain" } });
}
