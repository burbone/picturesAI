import { useEffect, useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { apiFetch } from "../api/client";
import type { ImageResponse } from "../api/types";
import ImageCard from "../components/ImageCard";

export default function Recommendations() {
  const { userId } = useAuth();
  const [items, setItems] = useState<ImageResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!userId) return;

    setLoading(true);
    setError(null);

    apiFetch<ImageResponse[]>("/api/v1/recommendations?limit=20", {
      headers: { "X-User-Id": userId },
    })
      .then((data) => {
        if (cancelled) return;
        setItems(Array.isArray(data) ? data : []);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err?.message || "Не удалось загрузить рекомендации");
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [userId]);

  return (
    <div>
      <h1>Рекомендации</h1>

      {loading && <p className="muted">Загрузка...</p>}
      {error && <div className="banner error">{error}</div>}

      {!loading && !error && items.length === 0 && (
        <p className="muted">
          Сгенерируйте больше картинок, чтобы получить рекомендации.
        </p>
      )}

      {items.length > 0 && (
        <div className="grid">
          {items.map((it) => (
            <ImageCard
              key={it.id}
              thumbnailUrl={it.thumbnailUrl}
              imageUrl={it.imageUrl}
              prompt={it.prompt}
              tags={it.tags}
            />
          ))}
        </div>
      )}
    </div>
  );
}