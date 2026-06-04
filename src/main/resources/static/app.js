const state = {
  health: null,
  projects: [],
  decisions: [],
  sources: [],
  activeProjectId: localStorage.getItem("devcontext.activeProjectId") || "",
  activePanel: "home",
  commandMode: "review",
};

const commandHints = {
  review: "Routes to the Code Review workspace.",
  decision: "Routes to Decision Memory.",
  knowledge: "Routes to Knowledge RAG.",
};

const commandSamples = {
  review: "Review this diff for null pointer, transaction, idempotency, cache, and test risks.",
  decision: "For a payment history list, should I reuse the cursor pagination decision?",
  knowledge: "Why should DevContext core business depend on interfaces and keep external technologies in adapters?",
};

const sampleDiff = [
  "diff --git a/src/main/java/com/acme/order/OrderService.java b/src/main/java/com/acme/order/OrderService.java",
  "@@",
  "+Order order = orderRepository.findById(orderId);",
  "+paymentClient.charge(request);",
  "+return order.getTotal();",
].join("\n");

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function asJson(value) {
  return JSON.stringify(value, null, 2);
}

function toNumberOrNull(value) {
  const trimmed = String(value ?? "").trim();
  if (!trimmed) {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function splitCsv(value) {
  return String(value ?? "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.success === false) {
    const message = payload?.message || `${response.status} ${response.statusText}`;
    throw new Error(message);
  }
  return payload?.data;
}

function showNotice(message, type = "info") {
  const notice = $("#notice");
  if (!notice) {
    return;
  }
  notice.hidden = false;
  notice.className = `notice${type === "error" ? " error" : ""}`;
  notice.textContent = message;
  clearTimeout(showNotice.timer);
  showNotice.timer = setTimeout(() => {
    notice.hidden = true;
  }, 4500);
}

async function withButton(button, action) {
  if (!button) {
    return action();
  }
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "Working";
  try {
    return await action();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

function setPanel(panel) {
  state.activePanel = panel;
  $$(".nav-button").forEach((button) => {
    button.classList.toggle("active", button.dataset.panel === panel);
  });
  $$(".panel-view").forEach((element) => {
    element.classList.toggle("active", element.id === `panel-${panel}`);
  });
}

function setCommandMode(mode) {
  state.commandMode = mode;
  $$(".mode-button").forEach((button) => {
    button.classList.toggle("active", button.dataset.commandMode === mode);
  });
  $("#commandHint").textContent = commandHints[mode] || commandHints.review;
  $("#heroIntent").value = commandSamples[mode] || commandSamples.review;
}

async function loadHealth() {
  try {
    state.health = await api("/api/health");
    renderHealth();
  } catch (error) {
    state.health = null;
    renderHealth(error);
  }
}

function renderHealth(error) {
  const dot = $("#healthDot");
  const text = $("#healthText");
  if (!dot || !text) {
    return;
  }

  dot.classList.toggle("ok", Boolean(state.health));
  dot.classList.toggle("error", Boolean(error));
  text.textContent = state.health ? "Service online" : "Service unavailable";

  $("#llmChip").textContent = state.health
    ? `${state.health.llmProvider || "llm"} · ${state.health.llmModel || state.health.llmClient || "model"}`
    : "LLM unavailable";
  $("#vectorChip").textContent = state.health
    ? `Vector · ${state.health.vectorProvider || "unknown"}`
    : "Vector unavailable";
}

async function loadProjects() {
  state.projects = await api("/api/projects");
  if (state.activeProjectId && !state.projects.some((project) => String(project.id) === String(state.activeProjectId))) {
    state.activeProjectId = "";
    localStorage.removeItem("devcontext.activeProjectId");
  }
  renderProjects();
}

function activeProject() {
  return state.projects.find((project) => String(project.id) === String(state.activeProjectId)) || null;
}

function renderProjects() {
  $("#projectChip").textContent = `${state.projects.length} project${state.projects.length === 1 ? "" : "s"}`;
  const select = $("#activeProjectSelect");
  select.innerHTML = `<option value="">No project selected</option>${state.projects
    .map((project) => `<option value="${escapeHtml(project.id)}">${escapeHtml(project.name)}</option>`)
    .join("")}`;
  select.value = state.activeProjectId;

  const currentProject = activeProject();
  $("#drawerProjectName").textContent = currentProject?.name || "No project";

  $("#projectList").innerHTML = state.projects.map((project) => projectItem(project)).join("")
    || `<div class="empty-state">No projects yet.</div>`;
}

function projectItem(project) {
  const selected = String(project.id) === String(state.activeProjectId);
  return `
    <article class="list-item ${selected ? "selected" : ""}">
      <div class="item-top">
        <div>
          <div class="item-title">${escapeHtml(project.name)}</div>
          <div class="item-meta">
            <span class="pill">#${escapeHtml(project.id)}</span>
            <span>${escapeHtml(project.defaultBranch || "main")}</span>
          </div>
        </div>
        <button class="secondary-button" type="button" data-select-project="${escapeHtml(project.id)}">${selected ? "Selected" : "Select"}</button>
      </div>
      <div class="muted">${escapeHtml(project.rootPath)}</div>
    </article>
  `;
}

async function loadDecisions() {
  const status = $("#decisionStatusFilter")?.value ?? "active";
  const params = new URLSearchParams();
  if (status) {
    params.set("status", status);
  }
  if (state.activeProjectId) {
    params.set("projectId", state.activeProjectId);
  }
  state.decisions = await api(`/api/decisions${params.toString() ? `?${params}` : ""}`);
  renderDecisions();
}

function renderDecisions() {
  const activeCount = state.decisions.filter((decision) => decision.status === "active").length;
  $("#drawerDecisionCount").textContent = `${activeCount} active`;
  $("#decisionList").innerHTML = state.decisions.map((decision) => `
    <article class="list-item">
      <div class="item-top">
        <div>
          <div class="item-title">${escapeHtml(decision.title)}</div>
          <div class="item-meta">
            <span class="pill ${escapeHtml(decision.status)}">${escapeHtml(decision.status)}</span>
            <span class="pill">${escapeHtml(decision.embeddingStatus || "embedding")}</span>
            ${(decision.tags || []).slice(0, 3).map((tag) => `<span>${escapeHtml(tag)}</span>`).join("")}
          </div>
        </div>
        <button class="secondary-button" type="button" data-load-decision="${escapeHtml(decision.id)}">Open</button>
      </div>
      <div class="muted">${escapeHtml(decision.decision || decision.scenario || "")}</div>
    </article>
  `).join("") || `<div class="empty-state">No matching Decision Cards.</div>`;
}

async function loadSources() {
  state.sources = await api("/api/knowledge-sources");
  renderSources();
}

function renderSources() {
  $("#drawerSourceCount").textContent = `${state.sources.length} source${state.sources.length === 1 ? "" : "s"}`;
  $("#knowledgeSources").innerHTML = state.sources.map((source) => `
    <article class="list-item">
      <div class="item-top">
        <div>
          <div class="item-title">${escapeHtml(source.name)}</div>
          <div class="item-meta">
            <span class="pill">#${escapeHtml(source.id)}</span>
            <span>${escapeHtml(source.sourceType)}</span>
          </div>
        </div>
        <button class="secondary-button" type="button" data-index-source="${escapeHtml(source.id)}">Index</button>
      </div>
      <div class="muted">${escapeHtml(source.rootPath || "")}</div>
    </article>
  `).join("") || `<div class="empty-state">No knowledge sources yet.</div>`;
}

function renderReview(detail) {
  const review = detail?.review || {};
  const issues = detail?.issues || [];

  $("#reviewResult").innerHTML = `
    <div class="list-item">
      <div class="item-top">
        <div>
          <div class="item-title">Review #${escapeHtml(review.id || "")}</div>
          <div class="item-meta">
            <span>Run #${escapeHtml(review.runId || "")}</span>
            <span>${escapeHtml(review.status || "completed")}</span>
          </div>
        </div>
      </div>
    </div>
    ${issues.map((issue) => issueCard(issue)).join("") || `<div class="empty-state">No issues were returned.</div>`}
  `;
  if (review.runId) {
    $("#runIdInput").value = review.runId;
  }
}

function issueCard(issue) {
  return `
    <article class="issue-card">
      <div class="item-top">
        <div>
          <div class="item-title">${escapeHtml(issue.title)}</div>
          <div class="item-meta">
            <span class="pill ${escapeHtml(issue.severity)}">${escapeHtml(issue.severity)}</span>
            <span class="pill ${escapeHtml(issue.status)}">${escapeHtml(issue.status)}</span>
            <span>${escapeHtml(issue.filePath || "unknown")}${issue.lineNumber ? `:${escapeHtml(issue.lineNumber)}` : ""}</span>
          </div>
        </div>
      </div>
      <div>${escapeHtml(issue.description)}</div>
      <div class="muted">${escapeHtml(issue.suggestion || "")}</div>
    </article>
  `;
}

function renderTimeline(events) {
  $("#runTimeline").innerHTML = (events || []).map((event) => `
    <div class="timeline-event">
      <span class="pill ${escapeHtml(event.status)}">${escapeHtml(event.status)}</span>
      <div>
        <div class="event-type">${escapeHtml(event.eventType)}</div>
        <div class="muted">${escapeHtml(event.inputSummary || "")}</div>
        <div>${escapeHtml(event.outputSummary || "")}</div>
      </div>
    </div>
  `).join("") || `<div class="empty-state">No events loaded.</div>`;
}

async function requireProject(action) {
  if (!state.activeProjectId) {
    showNotice("Select a project first.", "error");
    setPanel("project");
    return;
  }
  try {
    await action(state.activeProjectId);
  } catch (error) {
    showNotice(error.message, "error");
  }
}

function fillSample(kind) {
  if (kind === "project") {
    setPanel("project");
    $("#projectForm [name='name']").value = "devcontext";
    $("#projectForm [name='rootPath']").value = "D:\\CodeX\\DevContext";
    $("#projectForm [name='defaultBranch']").value = "main";
  }

  if (kind === "review") {
    setPanel("review");
    $("#reviewForm [name='baseBranch']").value = "main";
    $("#reviewForm [name='compareBranch']").value = "feature/payment-webhook";
    $("#reviewForm [name='diffText']").value = sampleDiff;
  }

  if (kind === "decision") {
    setPanel("decision");
    const query = commandSamples.decision;
    $("#decisionSearchForm [name='query']").value = query;
    $("#reuseAdviceForm [name='query']").value = query;
    $("#decisionSearchForm [name='tags']").value = "pagination, performance";
  }

  if (kind === "knowledge-source") {
    setPanel("knowledge");
    $("#knowledgeSourceForm [name='name']").value = "DevContext Docs";
    $("#knowledgeSourceForm [name='rootPath']").value = "D:\\CodeX\\DevContext\\docs";
    $("#knowledgeSourceForm [name='sourceType']").value = "markdown";
  }

  if (kind === "knowledge") {
    setPanel("knowledge");
    $("#knowledgeAskForm [name='query']").value = commandSamples.knowledge;
    $("#knowledgeAskForm [name='sourceId']").value = state.sources[0]?.id || "";
  }
}

function initNavigation() {
  $$(".nav-button").forEach((button) => {
    button.addEventListener("click", () => setPanel(button.dataset.panel));
  });

  $$(".mode-button").forEach((button) => {
    button.addEventListener("click", () => setCommandMode(button.dataset.commandMode));
  });

  $("#heroLaunchButton").addEventListener("click", () => {
    const mode = state.commandMode;
    const intent = $("#heroIntent").value;
    if (mode === "review") {
      setPanel("review");
      $("#reviewForm [name='diffText']").value = intent.includes("diff --git") ? intent : sampleDiff;
    }
    if (mode === "decision") {
      setPanel("decision");
      $("#decisionSearchForm [name='query']").value = intent;
      $("#reuseAdviceForm [name='query']").value = intent;
    }
    if (mode === "knowledge") {
      setPanel("knowledge");
      $("#knowledgeAskForm [name='query']").value = intent;
    }
  });

  document.addEventListener("click", async (event) => {
    const sampleButton = event.target.closest("[data-sample]");
    const projectButton = event.target.closest("[data-select-project]");
    const decisionButton = event.target.closest("[data-load-decision]");
    const indexButton = event.target.closest("[data-index-source]");

    if (sampleButton) {
      fillSample(sampleButton.dataset.sample);
    }

    if (projectButton) {
      state.activeProjectId = projectButton.dataset.selectProject;
      localStorage.setItem("devcontext.activeProjectId", state.activeProjectId);
      renderProjects();
      showNotice("Project selected.");
    }

    if (decisionButton) {
      await withButton(decisionButton, async () => {
        try {
          const decision = await api(`/api/decisions/${decisionButton.dataset.loadDecision}`);
          $("#decisionResult").textContent = asJson(decision);
        } catch (error) {
          showNotice(error.message, "error");
        }
      });
    }

    if (indexButton) {
      await withButton(indexButton, async () => {
        try {
          const result = await api(`/api/knowledge-sources/${indexButton.dataset.indexSource}/index`, { method: "POST" });
          $("#knowledgeResult").textContent = asJson(result);
          showNotice("Knowledge source indexed.");
        } catch (error) {
          showNotice(error.message, "error");
        }
      });
    }
  });
}

function initForms() {
  $("#refreshButton").addEventListener("click", () => refreshAll());

  $("#activeProjectSelect").addEventListener("change", (event) => {
    state.activeProjectId = event.target.value;
    if (state.activeProjectId) {
      localStorage.setItem("devcontext.activeProjectId", state.activeProjectId);
    } else {
      localStorage.removeItem("devcontext.activeProjectId");
    }
    renderProjects();
  });

  $("#reloadProjectsButton").addEventListener("click", () => loadProjects().catch((error) => showNotice(error.message, "error")));
  $("#loadDecisionsButton").addEventListener("click", () => loadDecisions().catch((error) => showNotice(error.message, "error")));
  $("#loadSourcesButton").addEventListener("click", () => loadSources().catch((error) => showNotice(error.message, "error")));

  $("#projectForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await withButton(event.submitter, async () => {
      try {
        const project = await api("/api/projects", {
          method: "POST",
          body: JSON.stringify({
            name: form.get("name"),
            rootPath: form.get("rootPath"),
            defaultBranch: form.get("defaultBranch") || "main",
          }),
        });
        state.activeProjectId = String(project.id);
        localStorage.setItem("devcontext.activeProjectId", state.activeProjectId);
        await loadProjects();
        showNotice("Project saved.");
      } catch (error) {
        showNotice(error.message, "error");
      }
    });
  });

  $("#contextStatusButton").addEventListener("click", () => requireProject(async (projectId) => {
    const data = await api(`/api/projects/${projectId}/context`);
    $("#contextResult").textContent = asJson(data);
  }));

  $("#contextGenerateButton").addEventListener("click", () => requireProject(async (projectId) => {
    const data = await api(`/api/projects/${projectId}/context/generate`, {
      method: "POST",
      body: JSON.stringify({ overwriteGenerated: true, overwriteManual: false }),
    });
    $("#contextResult").textContent = asJson(data);
  }));

  $("#contextItemsButton").addEventListener("click", () => requireProject(async (projectId) => {
    const data = await api(`/api/projects/${projectId}/context-items`);
    $("#contextResult").textContent = asJson(data);
  }));

  $("#reviewForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    await requireProject(async (projectId) => {
      const form = new FormData(event.currentTarget);
      await withButton(event.submitter, async () => {
        const result = await api(`/api/projects/${projectId}/reviews`, {
          method: "POST",
          body: JSON.stringify({
            baseBranch: form.get("baseBranch") || null,
            compareBranch: form.get("compareBranch") || null,
            diffText: form.get("diffText") || null,
            mode: form.get("mode") || "strict",
          }),
        });
        $("#reviewIdInput").value = result.reviewId;
        showNotice(`Review #${result.reviewId} created.`);
        const detail = await api(`/api/reviews/${result.reviewId}`);
        renderReview(detail);
      });
    });
  });

  $("#loadReviewButton").addEventListener("click", async () => {
    try {
      const reviewId = toNumberOrNull($("#reviewIdInput").value);
      if (!reviewId) {
        throw new Error("Review ID is required.");
      }
      const detail = await api(`/api/reviews/${reviewId}`);
      renderReview(detail);
    } catch (error) {
      showNotice(error.message, "error");
    }
  });

  $("#decisionSearchForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await withButton(event.submitter, async () => {
      try {
        const result = await api("/api/decisions/search", {
          method: "POST",
          body: JSON.stringify({
            query: form.get("query"),
            projectId: toNumberOrNull(state.activeProjectId),
            tags: splitCsv(form.get("tags")),
            topK: 5,
          }),
        });
        $("#decisionResult").textContent = asJson(result);
      } catch (error) {
        showNotice(error.message, "error");
      }
    });
  });

  $("#reuseAdviceForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await withButton(event.submitter, async () => {
      try {
        const result = await api("/api/decisions/reuse-advice", {
          method: "POST",
          body: JSON.stringify({
            query: form.get("query"),
            projectId: toNumberOrNull(state.activeProjectId),
            tags: [],
            topK: toNumberOrNull(form.get("topK")) || 5,
          }),
        });
        $("#decisionResult").textContent = result.advice || asJson(result);
        if (result.runId) {
          $("#runIdInput").value = result.runId;
        }
      } catch (error) {
        showNotice(error.message, "error");
      }
    });
  });

  $("#knowledgeSourceForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await withButton(event.submitter, async () => {
      try {
        const source = await api("/api/knowledge-sources", {
          method: "POST",
          body: JSON.stringify({
            name: form.get("name"),
            rootPath: form.get("rootPath"),
            sourceType: form.get("sourceType"),
          }),
        });
        $("#knowledgeResult").textContent = asJson(source);
        await loadSources();
        showNotice("Knowledge source added.");
      } catch (error) {
        showNotice(error.message, "error");
      }
    });
  });

  $("#knowledgeAskForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await withButton(event.submitter, async () => {
      try {
        const result = await api("/api/knowledge/ask", {
          method: "POST",
          body: JSON.stringify({
            query: form.get("query"),
            sourceId: toNumberOrNull(form.get("sourceId")),
            topK: 5,
          }),
        });
        $("#knowledgeResult").textContent = result.answer || asJson(result);
        if (result.runId) {
          $("#runIdInput").value = result.runId;
        }
      } catch (error) {
        showNotice(error.message, "error");
      }
    });
  });

  $("#loadRunButton").addEventListener("click", async () => {
    try {
      const runId = toNumberOrNull($("#runIdInput").value);
      if (!runId) {
        throw new Error("Run ID is required.");
      }
      const [run, events] = await Promise.all([
        api(`/api/agent-runs/${runId}`),
        api(`/api/agent-runs/${runId}/events`),
      ]);
      renderTimeline(events);
      $("#runResult").textContent = asJson(run);
    } catch (error) {
      showNotice(error.message, "error");
    }
  });
}

async function refreshAll() {
  await Promise.allSettled([
    loadHealth(),
    loadProjects(),
    loadDecisions(),
    loadSources(),
  ]);
}

document.addEventListener("DOMContentLoaded", async () => {
  initNavigation();
  initForms();
  setCommandMode("review");
  setPanel("home");
  await refreshAll();
});
