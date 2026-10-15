import { useAuth } from "../auth/AuthContext";

export default function Login() {
  const { login } = useAuth();

  return (
    <div className="login-wrap">
      <div className="login-box">
        <h1>AI Image Gallery</h1>
        <p className="muted">Войдите, чтобы генерировать изображения</p>
        <div style={{ marginTop: 16 }}>
          <button onClick={login}>Войти</button>
        </div>
        <p className="muted" style={{ marginTop: 16 }}>
          Нет аккаунта? Нажмите «Войти» и выберите «Register» на странице Keycloak.
        </p>
      </div>
    </div>
  );
}