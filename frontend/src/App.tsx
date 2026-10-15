import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import Layout from "./components/Layout";
import Login from "./pages/Login";
import Generate from "./pages/Generate";
import Recommendations from "./pages/Recommendations";
import Profile from "./pages/Profile";

function Loading() {
  return <div className="page">Загрузка...</div>;
}

function ProtectedLayout() {
  const { initialized, authenticated } = useAuth();
  if (!initialized) return <Loading />;
  if (!authenticated) return <Navigate to="/login" replace />;
  return <Layout />;
}

function Root() {
  const { initialized, authenticated } = useAuth();
  if (!initialized) return <Loading />;
  return (
    <Routes>
      <Route
        path="/login"
        element={authenticated ? <Navigate to="/" replace /> : <Login />}
      />
      <Route element={<ProtectedLayout />}>
        <Route path="/" element={<Generate />} />
        <Route path="/recommendations" element={<Recommendations />} />
        <Route path="/profile" element={<Profile />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Root />
      </BrowserRouter>
    </AuthProvider>
  );
}