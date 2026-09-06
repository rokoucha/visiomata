import {
  BMLBrowser,
  OverlayInputApplication,
  keyCodeToAribKey,
  type EPG,
  type Indicator,
} from "../node_modules/web-bml/client/bml_browser";
import type { ResponseMessage } from "../node_modules/web-bml/server/ws_api";

interface AndroidBmlBridge {
  onInvisibleChanged(invisible: boolean): void;
  onUsedKeyListChanged(usedKeyList: string): void;
  onVideoRectChanged(left: number, top: number, width: number, height: number): void;
}

interface AndroidBmlNetworkBridge {
  isConnected(): boolean;
  request(requestId: string, method: "GET" | "POST", uri: string, bodyBase64: string): void;
}

declare global {
  interface Window {
    AndroidBml?: AndroidBmlBridge;
    AndroidBmlNetwork?: AndroidBmlNetworkBridge;
    AndroidBmlNetworkResponse?: (requestId: string, response: string) => void;
    AndroidBmlMessage?: (message: string) => void;
    AndroidBmlMessages?: (messages: ResponseMessage[]) => void;
  }
}

const root = requiredElement<HTMLElement>("data-broadcasting-browser");
const videoContainer = requiredDescendant<HTMLElement>(root, ".arib-video-container");
const invisibleVideoContainer = requiredDescendant<HTMLElement>(
  root,
  ".arib-video-invisible-container",
);
const content = requiredDescendant<HTMLElement>(root, ".data-broadcasting-browser-content");
const inputContainer = requiredDescendant<HTMLElement>(root, ".overlay-input-container");
const receivingStatus = requiredDescendant<HTMLElement>(root, ".remote-control-receiving-status");
const networkingStatus = requiredDescendant<HTMLElement>(root, ".remote-control-networking-status");

const epg: EPG = {
  tune: () => false,
};

class AndroidIndicator implements Indicator {
  private receiving = false;
  private networkingGet = false;
  private networkingPost = false;

  setUrl(_name: string, _loading: boolean): void {}

  setReceivingStatus(receiving: boolean): void {
    this.receiving = receiving;
    this.update();
  }

  setNetworkingGetStatus(get: boolean): void {
    this.networkingGet = get;
    this.update();
  }

  setNetworkingPostStatus(post: boolean): void {
    this.networkingPost = post;
    this.update();
  }

  setEventName(_eventName: string | null): void {}

  private update(): void {
    receivingStatus.hidden = !this.receiving;
    networkingStatus.hidden = !(this.networkingGet || this.networkingPost);
  }
}

const inputApplication = new OverlayInputApplication(inputContainer);
const network = window.AndroidBmlNetwork;
let nextNetworkRequestId = 0;
const pendingNetworkRequests = new Map<string, (response: string) => void>();
window.AndroidBmlNetworkResponse = (requestId, response) => {
  const resolve = pendingNetworkRequests.get(requestId);
  if (resolve == null) return;
  pendingNetworkRequests.delete(requestId);
  resolve(response);
};
function requestNetwork(method: "GET" | "POST", uri: string, bodyBase64 = ""): Promise<string> {
  if (network == null) return Promise.resolve('{"error":true}');
  const requestId = (++nextNetworkRequestId).toString();
  return new Promise((resolve) => {
    pendingNetworkRequests.set(requestId, resolve);
    network.request(requestId, method, uri, bodyBase64);
  });
}
const ip = network == null
  ? undefined
  : {
      isIPConnected: () => network.isConnected() ? 1 : 0,
      getConnectionType: () => 403,
      get: async (uri: string) => {
        const result = parseNetworkResponse(await requestNetwork("GET", uri));
        if (result == null) return {};
        return {
          statusCode: result.statusCode,
          headers: new Headers(result.headers),
          response: decodeBase64(result.response),
        };
      },
      transmitTextDataOverIP: async (uri: string, body: Uint8Array<ArrayBuffer>) => {
        const result = parseNetworkResponse(await requestNetwork("POST", uri, encodeBase64(body)));
        if (result == null) {
          return { resultCode: Number.NaN, statusCode: "", response: new Uint8Array() };
        }
        return {
          resultCode: 1,
          statusCode: result.statusCode.toString(),
          response: decodeBase64(result.response),
        };
      },
    };
const storedLogLevel = localStorage.getItem("logLevel");
const logLevel =
  storedLogLevel === "error" ||
  storedLogLevel === "warn" ||
  storedLogLevel === "info" ||
  storedLogLevel === "log" ||
  storedLogLevel === "debug"
    ? storedLogLevel
    : undefined;
const browser = new BMLBrowser({
  containerElement: content,
  mediaElement: videoContainer,
  videoPlaneModeEnabled: true,
  fonts: {
    roundGothic: { source: "url('/web-bml/KosugiMaru-Regular.woff2'), local('MS Gothic')" },
    boldRoundGothic: { source: "url('/web-bml/KosugiMaru-Bold.woff2'), local('MS Gothic')" },
    squareGothic: { source: "url('/web-bml/Kosugi-Regular.woff2'), local('MS Gothic')" },
  },
  epg,
  ip,
  indicator: new AndroidIndicator(),
  inputApplication,
  log: { level: logLevel },
});

function notifyVideoRect(): void {
  const rect = browser.getVideoElement()?.getBoundingClientRect();
  window.AndroidBml?.onVideoRectChanged(
    rect?.left ?? 0,
    rect?.top ?? 0,
    rect?.width ?? 0,
    rect?.height ?? 0,
  );
}

function notifyVideoRectAfterLayout(): void {
  requestAnimationFrame(() => requestAnimationFrame(notifyVideoRect));
  window.setTimeout(notifyVideoRect, 300);
}

browser.addEventListener("invisible", (event) => {
  const invisible = event.detail;
  window.AndroidBml?.onInvisibleChanged(invisible);
  if (invisible) {
    invisibleVideoContainer.appendChild(videoContainer);
  } else {
    browser.getVideoElement()?.appendChild(videoContainer);
  }
  notifyVideoRectAfterLayout();
});

browser.addEventListener("usedkeylistchanged", (event) => {
  // Use web-bml's parsed event payload as the source of truth. Reading the
  // computed style back through Content's internal DOM can observe the
  // receiver default (basic + data-button) instead of the document value.
  const groups = [...event.detail.usedKeyList];
  window.AndroidBml?.onUsedKeyListChanged(groups.length === 0 ? "none" : groups.join(" "));
});

browser.addEventListener("load", (event) => {
  root.style.width = `${event.detail.resolution.width}px`;
  root.style.height = `${event.detail.resolution.height}px`;
  notifyVideoRectAfterLayout();
});

browser.addEventListener("videochanged", notifyVideoRect);
window.addEventListener("resize", notifyVideoRectAfterLayout);

window.addEventListener("keydown", (event) => {
  if (inputApplication.isLaunching || event.altKey || event.ctrlKey || event.metaKey) return;
  const key = keyCodeToAribKey(event.key);
  if (key === -1) return;
  event.preventDefault();
  browser.content.processKeyDown(key);
});

window.addEventListener("keyup", (event) => {
  const key = keyCodeToAribKey(event.key);
  if (key === -1) return;
  if (!event.altKey && !event.ctrlKey && !event.metaKey) event.preventDefault();
  browser.content.processKeyUp(key);
});

window.AndroidBmlMessage = (message) => browser.emitMessage(JSON.parse(message) as ResponseMessage);
window.AndroidBmlMessages = (messages) => {
  for (const message of messages) browser.emitMessage(message);
};
window.addEventListener("pagehide", () => {
  pendingNetworkRequests.clear();
  browser.destroy();
}, { once: true });

function requiredElement<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (element == null) throw new Error(`Missing #${id}`);
  return element as T;
}

function requiredDescendant<T extends HTMLElement>(parent: ParentNode, selector: string): T {
  const element = parent.querySelector(selector);
  if (element == null) throw new Error(`Missing ${selector}`);
  return element as T;
}

type NetworkResponse = { statusCode: number; headers: Record<string, string>; response: string };

function parseNetworkResponse(json: string): NetworkResponse | null {
  try {
    const value = JSON.parse(json) as NetworkResponse & { error?: boolean };
    return value.error === true ? null : value;
  } catch {
    return null;
  }
}

function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function decodeBase64(value: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}
