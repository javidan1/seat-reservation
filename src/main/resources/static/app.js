"use strict";

/**
 * Live seat map. State flows one way: the server is the truth, this page renders it.
 * - SSE pushes committed changes with a per-seat version; stale/duplicate events are dropped.
 * - A lapsed hold produces no server event by design; every second we locally flip seats whose
 *   holdExpiresAt has passed. The next snapshot or sweep event confirms it.
 * - On (re)connect we re-fetch the snapshot, so a missed event can never wedge the page.
 */

const $ = (id) => document.getElementById(id);

const userRef = (() => {
  let u = localStorage.getItem("seatres-user");
  if (!u) {
    u = "guest-" + Math.random().toString(36).slice(2, 7);
    localStorage.setItem("seatres-user", u);
  }
  return u;
})();

let eventId = null;
const seats = new Map(); // seatId -> {id,rowLabel,seatNumber,status,holdExpiresAt,version,el}
const selection = new Set();
let myHold = null; // {id, seatIds, remainingSeconds}
let countdownTimer = null;
let myWait = null; // {id, seatId}
let waitPollTimer = null;
let pendingWaitSeat = null;

$("user-ref").textContent = userRef;

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function init() {
  const events = (await api("GET", "/api/events")).body;
  const event = events[0];
  eventId = event.id;
  $("event-name").textContent = event.name;
  $("event-date").textContent = new Date(event.startsAt).toLocaleString();
  await refreshSnapshot();
  connectStream();
  setInterval(localExpirySweep, 1000);
}

async function refreshSnapshot() {
  const list = (await api("GET", `/api/events/${eventId}/seats`)).body;
  for (const seat of list) {
    upsertSeat(seat, /*fromSnapshot=*/ true);
  }
  renderAll();
}

function connectStream() {
  const source = new EventSource(`/api/events/${eventId}/stream`);
  source.addEventListener("seat-change", (message) => {
    for (const change of JSON.parse(message.data)) {
      const known = seats.get(change.seatId);
      if (known && change.version <= known.version) continue; // stale — drop
      upsertSeat({ ...change, id: change.seatId });
    }
    renderAll();
  });
  source.onopen = () => refreshSnapshot(); // heal anything missed while disconnected
  // EventSource reconnects on its own; onopen re-syncs.
}

function upsertSeat(seat, fromSnapshot = false) {
  const existing = seats.get(seat.id);
  if (existing && !fromSnapshot && seat.version <= existing.version) return;
  if (existing && fromSnapshot && seat.version < existing.version) return;
  seats.set(seat.id, { ...existing, ...seat });
}

function localExpirySweep() {
  const now = Date.now();
  let dirty = false;
  for (const seat of seats.values()) {
    if (seat.status === "HELD" && seat.holdExpiresAt && Date.parse(seat.holdExpiresAt) <= now) {
      seat.status = "AVAILABLE";
      seat.holdExpiresAt = null;
      dirty = true;
    }
  }
  if (dirty) renderAll();
}

function renderAll() {
  const map = $("seat-map");
  if (!map.dataset.built) {
    buildGrid(map);
    map.dataset.built = "1";
  }
  for (const seat of seats.values()) {
    const el = seat.el;
    if (!el) continue;
    el.className = "seat";
    if (myHold && myHold.seatIds.includes(seat.id) && seat.status !== "SOLD") {
      el.classList.add("mine");
    } else if (selection.has(seat.id) && seat.status === "AVAILABLE") {
      el.classList.add("selected");
    } else if (seat.status === "HELD") {
      el.classList.add("held");
    } else if (seat.status === "SOLD") {
      el.classList.add("sold");
    }
    if (myWait && myWait.seatId === seat.id) {
      el.classList.add("waited");
    }
    el.disabled = seat.status === "SOLD"; // held seats stay clickable: they open the waitlist
  }
  // Drop selected seats someone else grabbed.
  for (const id of [...selection]) {
    const seat = seats.get(id);
    if (!seat || seat.status !== "AVAILABLE") selection.delete(id);
  }
  $("selection-summary").textContent = selection.size
    ? `Selected: ${labelsOf([...selection])}`
    : "Click available seats to select (up to 8).";
  $("hold-btn").disabled = selection.size === 0 || !!myHold;
}

function buildGrid(map) {
  const ordered = [...seats.values()].sort(
    (a, b) => a.rowLabel.localeCompare(b.rowLabel) || a.seatNumber - b.seatNumber
  );
  let currentRow = null;
  for (const seat of ordered) {
    if (seat.rowLabel !== currentRow) {
      currentRow = seat.rowLabel;
      const label = document.createElement("span");
      label.className = "row-label";
      label.textContent = currentRow;
      map.appendChild(label);
    }
    const el = document.createElement("button");
    el.className = "seat";
    el.title = `${seat.rowLabel}${seat.seatNumber}`;
    el.addEventListener("click", () => toggleSeat(seat.id));
    map.appendChild(el);
    seat.el = el;
  }
}

function labelsOf(ids) {
  return ids
    .map((id) => seats.get(id))
    .filter(Boolean)
    .sort((a, b) => a.rowLabel.localeCompare(b.rowLabel) || a.seatNumber - b.seatNumber)
    .map((s) => `${s.rowLabel}${s.seatNumber}`)
    .join(", ");
}

function toggleSeat(id) {
  if (myHold) return;
  const seat = seats.get(id);
  if (!seat) return;
  if (seat.status === "HELD") {
    if (myWait) return; // one queue at a time
    pendingWaitSeat = id;
    $("join-seat-label").textContent = labelsOf([id]);
    showPanel("join");
    return;
  }
  if (seat.status !== "AVAILABLE") return;
  if (selection.has(id)) selection.delete(id);
  else if (selection.size < 8) selection.add(id);
  renderAll();
}

function toast(message) {
  const el = $("toast");
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (el.hidden = true), 5000);
}

function showPanel(which) {
  $("selection-box").hidden = which !== "select";
  $("hold-box").hidden = which !== "hold";
  $("booking-box").hidden = which !== "booked";
  $("join-box").hidden = which !== "join";
  $("waitlist-box").hidden = which !== "wait";
}

function startCountdown() {
  clearInterval(countdownTimer);
  countdownTimer = setInterval(async () => {
    myHold.remainingSeconds -= 1;
    if (myHold.remainingSeconds <= 0) {
      clearInterval(countdownTimer);
      toast("Your hold expired — the seats went back on sale.");
      endHold();
      return;
    }
    $("countdown").textContent = myHold.remainingSeconds;
  }, 1000);
}

function endHold() {
  myHold = null;
  clearInterval(countdownTimer);
  selection.clear();
  showPanel("select");
  renderAll();
}

// --- Waitlist flow ---

function adoptHold(hold, message) {
  myHold = {
    id: hold.id,
    seatIds: hold.seatIds,
    remainingSeconds: hold.remainingSeconds,
  };
  $("hold-seats").textContent = labelsOf(myHold.seatIds);
  $("countdown").textContent = myHold.remainingSeconds;
  showPanel("hold");
  startCountdown();
  if (message) toast(message);
  renderAll();
}

function endWait() {
  myWait = null;
  clearInterval(waitPollTimer);
  showPanel("select");
  renderAll();
}

async function pollWait() {
  if (!myWait) return;
  const res = await api("GET", `/api/waitlist/${myWait.id}`);
  if (res.status !== 200) return;
  const entry = res.body;
  if (entry.status === "WAITING") {
    $("wait-position").textContent = entry.position;
  } else if (entry.status === "GRANTED") {
    clearInterval(waitPollTimer);
    const hold = await api("GET", `/api/holds/${entry.grantedHoldId}`);
    myWait = null;
    if (hold.status === 200 && hold.body.status === "ACTIVE") {
      adoptHold(hold.body, `${entry.seatLabel} is yours — confirm before the countdown ends!`);
    } else {
      toast("Your turn came and went while you were away.");
      endWait();
    }
    await refreshSnapshot();
  } else {
    toast(`The wait for ${entry.seatLabel} is over — the seat was sold.`);
    endWait();
  }
}

$("join-btn").addEventListener("click", async () => {
  const res = await api("POST", `/api/events/${eventId}/waitlist`, {
    userRef,
    seatId: pendingWaitSeat,
  });
  if (res.status === 201) {
    myWait = { id: res.body.id, seatId: res.body.seatId };
    $("wait-seat-label").textContent = res.body.seatLabel;
    $("wait-position").textContent = res.body.position;
    showPanel("wait");
    clearInterval(waitPollTimer);
    waitPollTimer = setInterval(pollWait, 2000);
    renderAll();
  } else {
    toast(res.body?.message || "Could not join the waitlist.");
    showPanel("select");
    await refreshSnapshot();
  }
  pendingWaitSeat = null;
});

$("join-cancel-btn").addEventListener("click", () => {
  pendingWaitSeat = null;
  showPanel("select");
});

$("wait-cancel-btn").addEventListener("click", async () => {
  const res = await api("DELETE", `/api/waitlist/${myWait.id}`);
  if (res.status === 409) {
    // Already granted — the next poll adopts the hold.
    await pollWait();
    return;
  }
  endWait();
});

$("hold-btn").addEventListener("click", async () => {
  const res = await api("POST", `/api/events/${eventId}/holds`, {
    userRef,
    seatIds: [...selection],
  });
  if (res.status === 201) {
    myHold = {
      id: res.body.id,
      seatIds: res.body.seatIds,
      remainingSeconds: res.body.remainingSeconds,
    };
    $("hold-seats").textContent = labelsOf(myHold.seatIds);
    $("countdown").textContent = myHold.remainingSeconds;
    showPanel("hold");
    startCountdown();
    renderAll();
  } else if (res.status === 409) {
    toast("Too slow — someone grabbed " + labelsOf(res.body.unavailableSeatIds || []) + ".");
    await refreshSnapshot();
  } else {
    toast(res.body?.message || "Could not hold those seats.");
  }
});

$("confirm-btn").addEventListener("click", async () => {
  const res = await api("POST", `/api/holds/${myHold.id}/confirm`);
  if (res.status === 201 || res.status === 200) {
    clearInterval(countdownTimer);
    $("booking-summary").textContent = `${res.body.seatIds.length} seat(s): ${labelsOf(res.body.seatIds)}`;
    $("booking-id").textContent = res.body.id;
    myHold = null;
    selection.clear();
    showPanel("booked");
    await refreshSnapshot();
  } else if (res.status === 410) {
    toast("Your hold had already expired — seats are back on sale.");
    endHold();
    await refreshSnapshot();
  } else {
    toast(res.body?.message || "Confirm failed.");
  }
});

$("release-btn").addEventListener("click", async () => {
  await api("DELETE", `/api/holds/${myHold.id}`);
  endHold();
});

$("again-btn").addEventListener("click", () => {
  showPanel("select");
  renderAll();
});

init();
