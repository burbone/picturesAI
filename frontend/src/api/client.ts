import type { ApiErrorBody } from "./types";

export const API_BASE = "http://localhost:8080";
export const WS_BASE = "ws://localhost:8083";

interface ClientConfig {
  getToken: () => string | null;
  onUnauthorized: () => void;
}

let config: ClientConfig = {
  getToken: () => null,
  onUnauthorized: () => {},
};

export function configureClient(next: ClientConfig) {
  config = next;
}

export class ApiError extends Error {
  status: number;
  code: string | null;
  body: unknown;

  constructor(status: number, code: string | null, message: string, body: unknown) {
    super(message);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

async function parseBody(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export async function apiFetch<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const token = config.getToken();
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers });

  if (res.status === 401) {
    config.onUnauthorized();
    throw new ApiError(401, "unauthorized", "Требуется авторизация", null);
  }

  const body = await parseBody(res);

  if (!res.ok) {
    const eb = (body as ApiErrorBody) || {};
    const code = eb.code || null;
    const message = eb.message || eb.error || `Ошибка ${res.status}`;
    throw new ApiError(res.status, code, message, body);
  }

  return body as T;
}