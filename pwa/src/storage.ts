import { Data, initial } from "./model";
const channel =
  typeof window !== "undefined" && typeof BroadcastChannel !== "undefined"
    ? new BroadcastChannel("vervetwodo")
    : null;
const subscribers = new Set<() => void>();
channel?.addEventListener("message", () => subscribers.forEach((fn) => fn()));
let database: Promise<IDBDatabase>;
function open() {
  return (database ??= new Promise((resolve, reject) => {
    const req = indexedDB.open("vervetwodo-pwa", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("state");
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(Error("storageError"));
    req.onblocked = () => reject(Error("storageBlocked"));
  }));
}
export async function read(): Promise<Data> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const req = db.transaction("state").objectStore("state").get("data");
    req.onsuccess = () => resolve(req.result || initial());
    req.onerror = () => reject(Error("storageError"));
  });
}
// Read/modify/write in one transaction: IndexedDB serializes mutations across tabs.
export async function mutate(fn: (data: Data) => void): Promise<Data> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const tx = db.transaction("state", "readwrite"),
      store = tx.objectStore("state"),
      req = store.get("data");
    let result: Data;
    let error: unknown;
    req.onsuccess = () => {
      try {
        result = req.result || initial();
        fn(result);
        result.revision++;
        store.put(result, "data");
      } catch (e) {
        error = e;
        tx.abort();
      }
    };
    tx.oncomplete = () => {
      channel?.postMessage("changed");
      subscribers.forEach((fn) => fn());
      resolve(result);
    };
    tx.onabort = tx.onerror = () => reject(error || Error("storageError"));
  });
}
export function subscribe(fn: () => void) {
  subscribers.add(fn);
  return () => {
    subscribers.delete(fn);
  };
}

// Keep pending edits and task IDs with the workspace that created them.
export async function activateWorkspace(origin: string, defaultOrigin: string, address: string) {
  const db = await open();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction("state", "readwrite");
    const store = tx.objectStore("state");
    const current = store.get("data");
    current.onsuccess = () => {
      const previous: Data = current.result || initial();
      const lastOrigin = previous.syncOrigin || (previous.connected ? defaultOrigin : "");
      function activate(next: Data) {
        next.connected = true;
        next.syncOrigin = origin;
        next.syncAddress = address;
        next.revision = Math.max(previous.revision, next.revision) + 1;
        store.put(next, "data");
      }
      if (!lastOrigin || lastOrigin === origin) {
        activate(previous);
        return;
      }
      store.put(previous, "workspace:" + lastOrigin);
      const cached = store.get("workspace:" + origin);
      cached.onsuccess = () => {
        const next: Data = cached.result || initial();
        if (!cached.result) {
          next.preferences.language = previous.preferences.language;
          next.preferences.theme = previous.preferences.theme;
        }
        activate(next);
        store.delete("workspace:" + origin);
      };
    };
    tx.oncomplete = () => {
      channel?.postMessage("changed");
      subscribers.forEach(fn => fn());
      resolve();
    };
    tx.onerror = tx.onabort = () => reject(Error("storageError"));
  });
}
