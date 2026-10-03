// Fixture files are generated in code (never committed as binaries). Each returns the shape Playwright's
// `setInputFiles` expects. Magic bytes matter: the backend checks them (`%PDF-`, PNG signature, JPEG FF D8 FF).

export interface UploadFile {
  name: string;
  mimeType: string;
  buffer: Buffer;
}

/** A tiny but structurally valid one-page PDF. */
export function smallPdf(name = "coi.pdf"): UploadFile {
  const body = [
    "%PDF-1.4",
    "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
    "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj",
    "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj",
    "trailer << /Root 1 0 R >>",
    "%%EOF",
    "",
  ].join("\n");
  return { name, mimeType: "application/pdf", buffer: Buffer.from(body, "latin1") };
}

/** A 1x1 transparent PNG. */
export function smallPng(name = "coi.png"): UploadFile {
  const base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
  return { name, mimeType: "image/png", buffer: Buffer.from(base64, "base64") };
}

/** Plain text pretending to be a PDF: right extension, wrong magic bytes (server must answer 415). */
export function fakePdf(name = "fake.pdf"): UploadFile {
  return { name, mimeType: "application/pdf", buffer: Buffer.from("This is not a PDF, just text.\n", "utf8") };
}

/** A PDF-looking file of about `megabytes` MB: `%PDF-` followed by filler. Used to prove the proxy passes big bodies. */
export function largePdf(megabytes: number, name = "large.pdf"): UploadFile {
  const size = Math.floor(megabytes * 1024 * 1024);
  const buffer = Buffer.alloc(size, 0x20);
  buffer.write("%PDF-1.4\n", 0, "latin1");
  buffer.write("\n%%EOF\n", size - 7, "latin1");
  return { name, mimeType: "application/pdf", buffer };
}
