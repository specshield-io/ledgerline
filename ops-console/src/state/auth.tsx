import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { api, setCredentials, unwrap, type Credentials } from "../api/client";

export type Role = "ADMIN" | "VIEWER";

interface Session {
  username: string;
  roles: Role[];
}

interface AuthState {
  session: Session | null;
  isAdmin: boolean;
  signIn: (credentials: Credentials) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

/** HTTP Basic sign-in against GET /api/v1/admin/me. Credentials stay in memory only. */
export function AuthProvider({
  children,
  initial = null,
}: {
  children: ReactNode;
  initial?: Session | null;
}) {
  const [session, setSession] = useState<Session | null>(initial);

  const signIn = useCallback(async (credentials: Credentials) => {
    setCredentials(credentials);
    try {
      const me = await unwrap(api.GET("/api/v1/admin/me"));
      setSession({ username: me.username ?? credentials.username, roles: (me.roles ?? []) as Role[] });
    } catch (error) {
      setCredentials(null);
      throw error;
    }
  }, []);

  const signOut = useCallback(() => {
    setCredentials(null);
    setSession(null);
  }, []);

  const value = useMemo(
    () => ({ session, isAdmin: session?.roles.includes("ADMIN") ?? false, signIn, signOut }),
    [session, signIn, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth outside AuthProvider");
  return value;
}
