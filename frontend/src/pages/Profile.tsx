import { useEffect, useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { apiFetch } from "../api/client";
import type { BillingInfo, UserProfile } from "../api/types";

export default function Profile() {
  const { userId, logout } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [billing, setBilling] = useState<BillingInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [displayName, setDisplayName] = useState("");
  const [bio, setBio] = useState("");
  const [saving, setSaving] = useState(false);
  const [upgrading, setUpgrading] = useState(false);

  const loadAll = async () => {
    if (!userId) return;
    setLoading(true);
    setError(null);
    try {
      const p = await apiFetch<UserProfile>("/api/v1/users/me");
      setProfile(p);
      setDisplayName(p.displayName || "");
      setBio(p.bio || "");

      try {
        const b = await apiFetch<BillingInfo>(
          `/api/v1/billing/me?userId=${encodeURIComponent(userId)}`
        );
        setBilling(b);
      } catch {
        setBilling(null);
      }
    } catch (err: any) {
      setError(err?.message || "Не удалось загрузить профиль");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setInfo(null);
    try {
      const updated = await apiFetch<UserProfile>("/api/v1/users/me", {
        method: "PATCH",
        body: JSON.stringify({
          displayName: displayName || undefined,
          bio: bio || undefined,
        }),
      });
      setProfile(updated);
      setInfo("Профиль сохранён");
    } catch (err: any) {
      setError(err?.message || "Не удалось сохранить профиль");
    } finally {
      setSaving(false);
    }
  };

  const handleUpgrade = async () => {
    if (!userId) return;
    setUpgrading(true);
    setError(null);
    setInfo(null);
    try {
      await apiFetch(`/api/v1/internal/billing/plan/${userId}?plan=PRO`, {
        method: "POST",
      });
      setInfo("План обновлён до PRO");
      await loadAll();
    } catch (err: any) {
      setError(err?.message || "Не удалось обновить план");
    } finally {
      setUpgrading(false);
    }
  };

  const handleDelete = async () => {
    if (!confirm("Удалить аккаунт? Действие необратимо.")) return;
    setError(null);
    setInfo(null);
    try {
      await apiFetch("/api/v1/users/me", { method: "DELETE" });
      logout();
    } catch (err: any) {
      setError(err?.message || "Не удалось удалить аккаунт");
    }
  };

  if (loading) return <p className="muted">Загрузка...</p>;

  return (
    <div>
      <h1>Профиль</h1>

      {error && <div className="banner error">{error}</div>}
      {info && <div className="banner info">{info}</div>}

      {profile && (
        <>
          <div className="quota">
            <h2>Квота</h2>
            {billing ? (
              <p>
                План: <b>{billing.plan}</b>. Осталось:{" "}
                <b>
                  {billing.remaining}/{billing.dailyLimit}
                </b>{" "}
                сегодня.
              </p>
            ) : (
              <p className="muted">Нет данных о биллинге.</p>
            )}
            {(!billing || billing.plan !== "PRO") && (
              <div style={{ marginTop: 10 }}>
                <button
                  className="secondary"
                  onClick={handleUpgrade}
                  disabled={upgrading}
                >
                  {upgrading ? "Обновление..." : "Обновить до PRO"}
                </button>
              </div>
            )}
          </div>

          <h2>Данные профиля</h2>
          <p className="muted">
            Логин: {profile.username}
            {profile.plan ? ` · План: ${profile.plan}` : ""}
          </p>

          <div className="field">
            <label>Отображаемое имя</label>
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              maxLength={100}
            />
          </div>

          <div className="field">
            <label>О себе</label>
            <textarea
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              maxLength={500}
            />
          </div>

          <div className="row">
            <button onClick={handleSave} disabled={saving}>
              {saving ? "Сохранение..." : "Сохранить"}
            </button>
          </div>

          <div className="row" style={{ marginTop: 24 }}>
            <button className="danger" onClick={handleDelete}>
              Удалить аккаунт
            </button>
          </div>
        </>
      )}
    </div>
  );
}