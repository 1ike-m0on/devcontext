CREATE TABLE IF NOT EXISTS project (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL,
    language TEXT,
    framework TEXT,
    default_branch TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS app_config (
    config_key TEXT PRIMARY KEY,
    config_value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER,
    run_type TEXT NOT NULL,
    status TEXT NOT NULL,
    model_name TEXT,
    prompt_version TEXT,
    input_token_estimate INTEGER,
    output_token_estimate INTEGER,
    duration_ms INTEGER,
    error_message TEXT,
    created_at TEXT NOT NULL,
    finished_at TEXT
);

CREATE TABLE IF NOT EXISTS agent_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    input_summary TEXT,
    output_summary TEXT,
    status TEXT NOT NULL,
    duration_ms INTEGER,
    error_message TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS context_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER,
    project_id INTEGER,
    item_type TEXT NOT NULL,
    title TEXT NOT NULL,
    source TEXT NOT NULL,
    priority INTEGER NOT NULL,
    token_estimate INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS context_document (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    doc_type TEXT NOT NULL,
    file_path TEXT NOT NULL,
    generated INTEGER NOT NULL,
    status TEXT NOT NULL,
    source_commit TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_context_document_project_path
ON context_document (project_id, file_path);
