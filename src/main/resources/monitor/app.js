const timeline = document.querySelector("#timeline");
const sessionLabel = document.querySelector("#session-label");
const connectionState = document.querySelector("#connection-state");
const sessionList = document.querySelector("#session-list");
const template = document.querySelector("#message-template");

const renderedIds = new Set();
const activeSessions = new Map();
const unreadSessionIds = new Set();
let selectedSessionId = "";
let eventSource = null;

function setConnectionState(state) {
  connectionState.dataset.state = state;
  connectionState.textContent = state;
}

function setSessionLabel(sessionId) {
  const session = activeSessions.get(sessionId);
  sessionLabel.textContent = session ? session.name : "session: -";
}

function shortSessionId(sessionId) {
  return sessionId ? sessionId.slice(0, 8) : "-";
}

function renderEmptyState(text = "会話待機中") {
  if (timeline.children.length > 0) {
    return;
  }
  const empty = document.createElement("div");
  empty.className = "empty-state";
  empty.textContent = text;
  timeline.append(empty);
}

function clearEmptyState() {
  const empty = timeline.querySelector(".empty-state");
  if (empty) {
    empty.remove();
  }
}

function appendEventToTimeline(event) {
  if (!event || renderedIds.has(event.id) || !event.text) {
    return;
  }
  clearEmptyState();

  const node = template.content.firstElementChild.cloneNode(true);
  node.classList.add(event.speaker === "assistant" ? "assistant" : "caller");
  node.querySelector(".text").textContent = event.text;
  node.querySelector(".timestamp").textContent = formatTimestamp(event.timestamp);
  timeline.append(node);
  renderedIds.add(event.id);
  timeline.scrollTop = timeline.scrollHeight;
}

function renderEvent(event) {
  if (!event || !event.sessionId || !event.text) {
    return;
  }
  rememberSession({
    sessionId: event.sessionId,
    name: activeSessions.get(event.sessionId)?.name || activeSessions.get(event.sessionId)?.slotId || "session",
    slotId: activeSessions.get(event.sessionId)?.slotId || "session",
    state: activeSessions.get(event.sessionId)?.state || "active",
    startedAt: activeSessions.get(event.sessionId)?.startedAt || event.timestamp,
    endedAt: activeSessions.get(event.sessionId)?.endedAt || "",
  });

  if (!selectedSessionId) {
    selectSession(event.sessionId, { loadHistory: false });
  }

  if (event.sessionId !== selectedSessionId) {
    unreadSessionIds.add(event.sessionId);
    renderSessionList();
    return;
  }

  appendEventToTimeline(event);
}

function rememberSession(session) {
  if (!session || !session.sessionId) {
    return;
  }
  activeSessions.set(session.sessionId, {
    sessionId: session.sessionId,
    name: session.name || session.slotId || "session",
    slotId: session.slotId || "session",
    state: session.state || "active",
    startedAt: session.startedAt || "",
    endedAt: session.endedAt || "",
  });
}

function renderSessionList() {
  sessionList.replaceChildren();
  const sessions = [...activeSessions.values()];
  if (sessions.length === 0) {
    const empty = document.createElement("div");
    empty.className = "session-list-empty";
    empty.textContent = "通話なし";
    sessionList.append(empty);
    return;
  }

  for (const session of sessions) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "session-item";
    if (session.sessionId === selectedSessionId) {
      button.classList.add("selected");
    }
    if (unreadSessionIds.has(session.sessionId)) {
      button.classList.add("unread");
    }
    button.dataset.sessionId = session.sessionId;

    const slot = document.createElement("span");
    slot.className = "session-slot";
    slot.textContent = session.name || session.slotId || "session";

    const id = document.createElement("span");
    id.className = "session-id-short";
    id.textContent = shortSessionId(session.sessionId);

    const state = document.createElement("span");
    state.className = "session-state";
    state.dataset.state = session.state || "active";
    state.textContent = session.state === "closed" ? "ended" : "active";

    button.append(slot, id, state);
    button.addEventListener("click", () => {
      selectSession(session.sessionId);
    });
    sessionList.append(button);
  }
}

function selectSession(sessionId, options = {}) {
  const { loadHistory = true } = options;
  selectedSessionId = sessionId || "";
  unreadSessionIds.delete(selectedSessionId);
  setSessionLabel(selectedSessionId);
  renderSessionList();
  if (loadHistory && selectedSessionId) {
    loadSessionHistory(selectedSessionId).catch(() => {
      timeline.replaceChildren();
      renderedIds.clear();
      renderEmptyState("履歴を取得できません");
    });
  }
}

function formatTimestamp(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toLocaleTimeString("ja-JP", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

async function loadSessionHistory(sessionId) {
  const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`session history request failed: ${response.status}`);
  }
  const payload = await response.json();
  timeline.replaceChildren();
  renderedIds.clear();
  for (const event of payload.events || []) {
    appendEventToTimeline(event);
  }
  renderEmptyState();
}

async function loadLatestHistory() {
  const response = await fetch("/api/sessions/latest", { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`history request failed: ${response.status}`);
  }
  const payload = await response.json();
  if (!selectedSessionId && payload.latestSessionId) {
    selectSession(payload.latestSessionId, { loadHistory: false });
  }
  timeline.replaceChildren();
  renderedIds.clear();
  for (const event of payload.events || []) {
    appendEventToTimeline(event);
  }
  renderEmptyState();
}

async function loadActiveSessions() {
  const response = await fetch("/api/sessions", { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`sessions request failed: ${response.status}`);
  }
  const payload = await response.json();
  activeSessions.clear();
  for (const session of payload.sessions || []) {
    if (typeof session === "string") {
      rememberSession({ sessionId: session });
    } else {
      rememberSession(session);
    }
  }
  if (!selectedSessionId && activeSessions.size > 0) {
    selectSession(activeSessions.keys().next().value);
  } else {
    renderSessionList();
  }
}

function connectEvents() {
  if (eventSource) {
    eventSource.close();
  }
  eventSource = new EventSource("/events");
  setConnectionState("connecting");

  eventSource.addEventListener("open", () => {
    setConnectionState("connected");
  });

  eventSource.addEventListener("transcript", (message) => {
    setConnectionState("connected");
    renderEvent(JSON.parse(message.data));
  });

  eventSource.addEventListener("error", () => {
    setConnectionState("disconnected");
  });
}

loadActiveSessions()
  .catch(() => {
    renderSessionList();
  })
  .then(() => {
    if (selectedSessionId) {
      return loadSessionHistory(selectedSessionId);
    }
    return loadLatestHistory();
  })
  .catch(() => {
    renderEmptyState();
  })
  .finally(() => {
    connectEvents();
    setInterval(() => {
      loadActiveSessions().catch(renderSessionList);
    }, 2000);
  });
