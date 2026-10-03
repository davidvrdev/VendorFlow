/** One field-level validation problem from an RFC 9457 `errors` extension. */
export interface FieldError {
  field: string;
  message: string;
}

interface ApiErrorInit {
  status: number;
  title: string;
  detail?: string;
  requestId?: string;
  errors?: FieldError[];
}

/**
 * Error thrown by the API client layer. Built from an RFC 9457 `application/problem+json` body.
 * Everything on it is safe to show to a user: we only copy known string fields from the body and
 * never surface raw HTML, stack traces or unknown payloads.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail?: string;
  readonly requestId?: string;
  readonly errors?: FieldError[];

  constructor({ status, title, detail, requestId, errors }: ApiErrorInit) {
    super(detail ?? title);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
    this.detail = detail;
    this.requestId = requestId;
    this.errors = errors;
  }

  /** Parse a non-2xx response. Never throws; falls back to a generic message for non-JSON bodies. */
  static async fromResponse(response: Response): Promise<ApiError> {
    const headerRequestId = response.headers.get("X-Request-Id") ?? undefined;
    const fallback = new ApiError({
      status: response.status,
      title: genericTitle(response.status),
      requestId: headerRequestId,
    });

    const contentType = response.headers.get("Content-Type") ?? "";
    if (!/\bjson\b/i.test(contentType)) return fallback;

    let body: unknown;
    try {
      body = await response.json();
    } catch {
      return fallback;
    }
    if (typeof body !== "object" || body === null) return fallback;

    const problem = body as Record<string, unknown>;
    return new ApiError({
      status: response.status,
      title: asString(problem.title) ?? fallback.title,
      detail: asString(problem.detail),
      requestId: asString(problem.requestId) ?? headerRequestId,
      errors: parseFieldErrors(problem.errors),
    });
  }
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function parseFieldErrors(value: unknown): FieldError[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const parsed = value.flatMap((item): FieldError[] => {
    if (typeof item !== "object" || item === null) return [];
    const { field, message } = item as Record<string, unknown>;
    return typeof field === "string" && typeof message === "string" ? [{ field, message }] : [];
  });
  return parsed.length > 0 ? parsed : undefined;
}

function genericTitle(status: number): string {
  if (status === 401) return "You need to sign in to continue.";
  if (status === 403) return "You do not have permission to do that.";
  if (status === 404) return "We could not find what you were looking for.";
  if (status === 429) return "Too many requests. Please wait a moment and try again.";
  if (status >= 500) return "Something went wrong on our side. Please try again.";
  return "The request could not be completed.";
}
