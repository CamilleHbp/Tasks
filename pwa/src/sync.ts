import { Operation, project } from "./model";
import { read, mutate, activateWorkspace, subscribe } from "./storage";
import { updatePush } from "./push";
export type SyncStatus =
  "local" | "syncing" | "synced" | "pending" | "offline" | "error" | "signIn";
let busy = false;
let stream: EventSource | undefined;
let listener: (s: SyncStatus) => void = () => {};
export function onSync(fn: typeof listener) {
  listener = fn;
}
function apiUrl(path: string, origin?: string) {
  return "/api/" + path + (origin ? "?" + new URLSearchParams({ workspace: origin }) : "");
}
export async function discoverSync(): Promise<{syncOrigin: string; publicOrigin?: string; primaryWorkspace?: string}> {
  const response = await fetch("/.well-known/vervetwodo-sync", { cache: "no-store" });
  if (!response.ok) throw Error("syncError");
  return response.json();
}
export async function api(path: string, body: unknown = {}, workspace?: string) {
  const origin = workspace ?? (await read()).syncOrigin;
  const response = await fetch(apiUrl(path, origin), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(70000),
  });
  if (response.status === 401) throw Error("signIn");
  if (!response.ok) {
    if (response.status === 400 && (await response.json().catch(() => ({}))).error === "unknownSyncServer")
      throw Error("unknownSyncServer");
    throw Error(response.status === 429 ? "rateLimited" : "syncError");
  }
  const result = await response.json();
  if (result.error) throw Error("syncError");
  return result;
}
function batch(ops: Operation[]) {
  const result: Operation[] = [];
  let bytes = 256;
  for (const op of ops.slice(0, 200)) {
    const size = new TextEncoder().encode(JSON.stringify(op)).length + 1;
    if (size >= 198000) throw Error("storageLimit");
    if (bytes + size >= 198000) break;
    result.push(op);
    bytes += size;
  }
  return result;
}
export async function sync() {
  if (busy) return;
  const d = await read();
  if (!d.connected) {
    listener("local");
    return;
  }
  busy = true;
  listener("syncing");
  try {
    // A cross-tab lock prevents an older response from replacing a newer projection.
    const run = async () => {
      let current = await read();
      if (!current.connected) return;
      const workspace = current.syncOrigin;
      while (current.syncOrigin === workspace && current.outbox.length) {
        const ops = batch(current.outbox);
        const reply = await api("apply", { operations: ops }, workspace || "");
        await mutate((d) => {
          if (d.syncOrigin !== workspace) return;
          const ids = new Set(ops.map((o) => o.id));
          d.outbox = d.outbox.filter((o) => !ids.has(o.id));
          project(d, reply.state.records || []);
        });
        current = await read();
      }
      if (current.syncOrigin !== workspace) return;
      const reply = await api("check", {}, workspace || "");
      await mutate((d) => { if (d.syncOrigin === workspace) project(d, reply.state.records || []); });
      listener(reply.telemetry?.confirmed ? "synced" : "pending");
    };
    if (navigator.locks) await navigator.locks.request("vervetwodo-sync", run);
    else await run();
  } catch (e) {
    listener(
      !navigator.onLine
        ? "offline"
        : e instanceof Error && e.message === "signIn"
          ? "signIn"
          : "error",
    );
  } finally {
    busy = false;
  }
}
export async function connect(code: string, address: string) {
  let origin: string;
  try {
    const url = new URL(address.trim());
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname !== "/")
      throw Error();
    origin = url.origin;
  } catch { throw Error("invalidSyncUrl"); }
  const discovery = await discoverSync();
  const primary = discovery.primaryWorkspace || discovery.syncOrigin;
  // Discovery resolves the hosted web URL to its native sync server.
  const login = await api("login", { code }, origin);
  const workspace = login.syncOrigin || (origin === location.origin ? primary : origin);
  const previous = await read();
  if (previous.connected && (previous.syncOrigin || primary) !== workspace) {
    try { await api("logout", {}, previous.syncOrigin || primary); }
    catch (error) {
      if (!(error instanceof Error && error.message === "signIn")) {
        await api("logout", {}, workspace);
        throw error;
      }
    }
  }
  await activateWorkspace(workspace, primary, address.trim());
  watch(workspace);
  await sync();
  const current = await read();
  if (current.preferences.push) await updatePush(current);
}
export async function disconnect() {
  stream?.close();
  stream = undefined;
  await api("logout");
  await mutate((d) => {
    d.connected = false;
    d.preferences.push = false;
  });
  listener("local");
}
function watch(origin?: string) {
  const url = new URL(apiUrl("events", origin), location.href).href;
  if (stream?.url === url) return;
  stream?.close();
  stream = new EventSource(url);
  stream.onmessage = () => void sync();
  stream.addEventListener("ready", () => void sync());
  stream.onerror = () => {
    if (!navigator.onLine) listener("offline");
  };
}
export function startSync() {
  const refresh = () => void read().then((d) => {
    if (d.connected) watch(d.syncOrigin);
    else { stream?.close(); stream = undefined; }
  });
  refresh();
  const unsubscribe = subscribe(refresh);
  const online = () => void sync();
  window.addEventListener("online", online);
  const visible = () => {
    if (document.visibilityState === "visible") void sync();
  };
  document.addEventListener("visibilitychange", visible);
  void sync();
  return () => {
    stream?.close();
    stream = undefined;
    unsubscribe();
    window.removeEventListener("online", online);
    document.removeEventListener("visibilitychange", visible);
  };
}
