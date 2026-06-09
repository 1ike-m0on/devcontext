async function loadJson(endpoint, targetId) {
  const target = document.getElementById(targetId);
  target.textContent = "Loading...";
  try {
    const response = await fetch(endpoint, { headers: { "Accept": "application/json" } });
    const payload = await response.json();
    target.textContent = JSON.stringify(payload, null, 2);
  } catch (error) {
    target.textContent = error instanceof Error ? error.message : "Request failed";
  }
}

function bindDebugButtons() {
  document.querySelectorAll("[data-endpoint][data-target]").forEach((button) => {
    button.addEventListener("click", () => {
      loadJson(button.dataset.endpoint, button.dataset.target);
    });
  });
}

document.addEventListener("DOMContentLoaded", () => {
  bindDebugButtons();
  loadJson("/api/health", "healthResult");
  loadJson("/api/settings/llm", "llmResult");
});
