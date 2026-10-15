import { Link, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export default function Layout() {
  const { logout } = useAuth();
  return (
    <>
      <nav className="nav">
        <Link to="/">Генерация</Link>
        <Link to="/recommendations">Рекомендации</Link>
        <Link to="/profile">Профиль</Link>
        <span className="spacer" />
        <button className="secondary" onClick={logout}>
          Выйти
        </button>
      </nav>
      <main className="page">
        <Outlet />
      </main>
    </>
  );
}