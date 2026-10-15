import Keycloak from "keycloak-js";

export const keycloak = new Keycloak({
  url: "http://localhost:8090",
  realm: "ai-gallery",
  clientId: "frontend",
});

export async function initKeycloak(): Promise<boolean> {
  return keycloak.init({
    onLoad: "check-sso",
    pkceMethod: "S256",
    redirectUri: window.location.origin + "/",
  });
}

export function getToken(): string | null {
  return keycloak.token ?? null;
}

export function getUserId(): string | null {
  const sub = keycloak.tokenParsed?.sub;
  return typeof sub === "string" ? sub : null;
}

export function login(): Promise<void> {
  return keycloak.login({ redirectUri: window.location.origin + "/" });
}

export function logout(): Promise<void> {
  return keycloak.logout({ redirectUri: window.location.origin + "/login" });
}