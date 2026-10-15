import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  getToken,
  getUserId,
  initKeycloak,
  keycloak,
  login as kcLogin,
  logout as kcLogout,
} from "./keycloak";
import { configureClient } from "../api/client";

interface AuthState {
  initialized: boolean;
  authenticated: boolean;
  token: string | null;
  userId: string | null;
  login: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

let initPromise: Promise<boolean> | null = null;

function initOnce(): Promise<boolean> {
  if (!initPromise) initPromise = initKeycloak();
  return initPromise;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [initialized, setInitialized] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    initOnce()
      .then((authed) => {
        if (cancelled) return;
        setAuthenticated(authed);
        setToken(getToken());
        setInitialized(true);
      })
      .catch(() => {
        if (cancelled) return;
        setInitialized(true);
      });

    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).then(() => {
        setToken(getToken());
        setAuthenticated(keycloak.authenticated ?? false);
      }).catch(() => {
        setToken(null);
        setAuthenticated(false);
      });
    };

    keycloak.onAuthLogout = () => {
      setToken(null);
      setAuthenticated(false);
    };

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    configureClient({
      getToken: () => getToken(),
      onUnauthorized: () => {
        kcLogout().catch(() => {});
      },
    });
  }, []);

  const value = useMemo<AuthState>(() => ({
    initialized,
    authenticated,
    token,
    userId: token ? getUserId() : null,
    login: () => {
      kcLogin().catch(() => {});
    },
    logout: () => {
      kcLogout().catch(() => {});
    },
  }), [initialized, authenticated, token]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}