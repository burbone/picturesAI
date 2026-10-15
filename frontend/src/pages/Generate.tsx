import { useEffect, useRef, useState, type FormEvent } from "react";
import { useAuth } from "../auth/AuthContext";
import { keycloak } from "../auth/keycloak";
import { ApiError, WS_BASE, apiFetch } from "../api/client";
import type {
  CreateGenerationRequest,
  CreateGenerationResponse,
  CursorPage,
  GenerationStatus,
  GenerationView,
  WsGenerationEvent,
} from "../api/types";
import ImageCard from "../components/ImageCard";

const MODEL = "sdxl";
const SIZE = "1024x1024";
const COUNT = 1;
const IS_PUBLIC = false;

interface CurrentJob {
  jobId: string;
  status: GenerationStatus;
  prompt: string;
  imageUrl: string | null;
  thumbnailUrl: string | null;
}

function extractItems(data: unknown): GenerationView[] {
  if (Array.isArray(data)) return data as GenerationView[];
  if (data && typeof data === "object") {
    const obj = data as Record<string, unknown>;
    const candidates = ["items", "content", "data", "records", "results"];
    for (const key of candidates) {
      if (Array.isArray(obj[key])) return obj[key] as GenerationView[];
    }
  }
  return [];
}

export default function Generate() {
  const { token } = useAuth();
  const [prompt, setPrompt] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);
  const [job, setJob] = useState<CurrentJob | null>(null);
  const [history, setHistory] = useState<GenerationView[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);

  const loadHistory = async () => {
    setHistoryLoading(true);
    try {
      const data = await apiFetch<CursorPage<GenerationView>>(
        "/api/v1/generations?limit=10"
      );
      setHistory(extractItems(data));
    } catch {
      setHistory([]);
    } finally {
      setHistoryLoading(false);
    }
  };

  useEffect(() => {
    loadHistory();
    return () => {
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, []);

  const openSocket = async (jobId: string, jobPrompt: string) => {
    wsRef.current?.close();

    let t = token;
    try {
      if (keycloak.authenticated) {
        await keycloak.updateToken(30);
        t = keycloak.token ?? t;
      }
    } catch {
      // ignore
    }
    if (!t) {
      setJob((prev) =>
        prev && prev.jobId === jobId ? { ...prev, status: "FAILED" } : prev
      );
      return;
    }

    const url = `${WS_BASE}/ws/generations/${jobId}?token=${t}`;
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      setJob((prev) =>
        prev && prev.jobId === jobId ? { ...prev, status: "PROCESSING" } : prev
      );
    };

    ws.onmessage = (ev) => {
      let msg: WsGenerationEvent | null = null;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return;
      }
      if (!msg) return;

      if (msg.type === "generation.completed") {
        setJob((prev) =>
          prev && prev.jobId === jobId
            ? {
                ...prev,
                status: "COMPLETED",
                imageUrl: msg!.payload.imageUrl ?? null,
                thumbnailUrl: msg!.payload.thumbnailUrl ?? null,
              }
            : prev
        );
        loadHistory();
        ws.close();
      } else if (msg.type === "generation.failed") {
        setJob((prev) =>
          prev && prev.jobId === jobId ? { ...prev, status: "FAILED" } : prev
        );
        loadHistory();
        ws.close();
      } else if (msg.type === "generation.cancelled") {
        setJob((prev) =>
          prev && prev.jobId === jobId ? { ...prev, status: "CANCELLED" } : prev
        );
        loadHistory();
        ws.close();
      }
    };

    ws.onclose = () => {
      if (wsRef.current === ws) wsRef.current = null;
      setJob((prev) => {
        if (!prev || prev.jobId !== jobId) return prev;
        if (prev.status === "COMPLETED" || prev.status === "FAILED" || prev.status === "CANCELLED") {
          return prev;
        }
        return { ...prev, status: "FAILED" };
      });
    };

    ws.onerror = () => {};
  };

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!prompt.trim() || submitting) return;

    setSubmitting(true);
    setBanner(null);

    const body: CreateGenerationRequest = {
      prompt: prompt.trim(),
      model: MODEL,
      size: SIZE,
      count: COUNT,
      isPublic: IS_PUBLIC,
    };

    try {
      const res = await apiFetch<CreateGenerationResponse>(
        "/api/v1/generations",
        {
          method: "POST",
          headers: { "Idempotency-Key": crypto.randomUUID() },
          body: JSON.stringify(body),
        }
      );

      setJob({
        jobId: res.jobId,
        status: res.status || "QUEUED",
        prompt: body.prompt,
        imageUrl: null,
        thumbnailUrl: null,
      });

      openSocket(res.jobId, body.prompt);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 402) setBanner("Квота исчерпана");
        else if (err.status === 409) setBanner("Такая задача уже в работе");
        else if (err.status === 422) setBanner("Некорректный запрос");
        else setBanner(err.message || "Ошибка");
      } else {
        setBanner("Сеть недоступна");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const statusLabel =
    job?.status === "QUEUED"
      ? "В очереди..."
      : job?.status === "PROCESSING"
      ? "Генерация..."
      : job?.status === "COMPLETED"
      ? "Готово"
      : job?.status === "FAILED"
      ? "Ошибка генерации"
      : job?.status === "CANCELLED"
      ? "Отменено"
      : "";

  return (
    <div>
      <h1>Генерация</h1>

      {banner && <div className="banner error">{banner}</div>}

      <form onSubmit={handleSubmit}>
        <div className="field">
          <label>Промпт</label>
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder="Опишите изображение..."
            maxLength={1000}
          />
        </div>
        <div className="row">
          <button type="submit" disabled={submitting || !prompt.trim()}>
            {submitting ? "Отправка..." : "Сгенерировать"}
          </button>
          {job && <span className="muted">{statusLabel}</span>}
        </div>
      </form>

      {job && (
        <div style={{ marginTop: 16 }}>
          {job.status === "COMPLETED" && job.imageUrl ? (
            <div style={{ maxWidth: 360 }}>
              <ImageCard
                thumbnailUrl={job.thumbnailUrl}
                imageUrl={job.imageUrl}
                prompt={job.prompt}
              />
            </div>
          ) : (
            <div className="banner info">
              {statusLabel}
              {job.jobId ? (
                <span className="muted"> · job {job.jobId.slice(0, 8)}</span>
              ) : null}
            </div>
          )}
        </div>
      )}

      <h2 style={{ marginTop: 32 }}>Последние генерации</h2>

      {historyLoading && <p className="muted">Загрузка...</p>}

      {!historyLoading && history.length === 0 && (
        <p className="muted">Пока нет генераций.</p>
      )}

      {history.length > 0 && (
        <div className="grid">
          {history.map((g) => (
            <ImageCard
              key={g.id}
              thumbnailUrl={g.thumbnailUrl}
              imageUrl={g.imageUrl}
              prompt={g.prompt}
              status={g.status}
            />
          ))}
        </div>
      )}
    </div>
  );
}