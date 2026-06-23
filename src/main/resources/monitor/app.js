const timeline = document.querySelector("#timeline");
const sessionLabel = document.querySelector("#session-label");
const connectionState = document.querySelector("#connection-state");
const template = document.querySelector("#message-template");

const renderedIds = new Set();
let currentSessionId = "";
let eventSource = null;

function setConnectionState(state) {
  connectionState.dataset.state = state;
  connectionState.textContent = state;
}

function setSessionLabel(sessionId) {
  currentSessionId = sessionId || "";
  sessionLabel.textContent = `session: ${currentSessionId || "-"}`;
}

function renderEmptyState() {
  if (timeline.children.length > 0) {
    return;
  }
  const empty = document.createElement("div");
  empty.className = "empty-state";
  empty.textContent = "会話待機中";
  timeline.append(empty);
}

function clearEmptyState() {
  const empty = timeline.querySelector(".empty-state");
  if (empty) {
    empty.remove();
  }
}

function renderEvent(event) {
  if (!event || renderedIds.has(event.id) || !event.text) {
    return;
  }
  if (currentSessionId && event.sessionId !== currentSessionId) {
    timeline.replaceChildren();
    renderedIds.clear();
  }
  setSessionLabel(event.sessionId);
  clearEmptyState();

  const node = template.content.firstElementChild.cloneNode(true);
  node.classList.add(event.speaker === "assistant" ? "assistant" : "caller");
  node.querySelector(".text").textContent = event.text;
  node.querySelector(".timestamp").textContent = formatTimestamp(event.timestamp);
  timeline.append(node);
  renderedIds.add(event.id);
  timeline.scrollTop = timeline.scrollHeight;
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

async function loadHistory() {
  const response = await fetch("/api/sessions/latest", { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`history request failed: ${response.status}`);
  }
  const payload = await response.json();
  setSessionLabel(payload.latestSessionId);
  timeline.replaceChildren();
  renderedIds.clear();
  for (const event of payload.events || []) {
    renderEvent(event);
  }
  renderEmptyState();
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

loadHistory()
  .catch(() => {
    renderEmptyState();
  })
  .finally(connectEvents);
