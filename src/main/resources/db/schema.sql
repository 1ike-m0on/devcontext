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

CREATE TABLE IF NOT EXISTS review_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    run_id INTEGER NOT NULL,
    base_branch TEXT NOT NULL,
    compare_branch TEXT NOT NULL,
    diff_hash TEXT NOT NULL,
    score REAL NOT NULL,
    summary TEXT NOT NULL,
    report_path TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS review_issue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    review_id INTEGER NOT NULL,
    severity TEXT NOT NULL,
    title TEXT NOT NULL,
    file_path TEXT,
    line_number INTEGER,
    description TEXT,
    impact TEXT,
    suggestion TEXT,
    confidence TEXT,
    status TEXT NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_review_issue_review_id
ON review_issue (review_id);

CREATE TABLE IF NOT EXISTS decision_card (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER,
    title TEXT NOT NULL,
    scenario TEXT NOT NULL,
    options_json TEXT NOT NULL,
    decision TEXT NOT NULL,
    reasons_json TEXT NOT NULL,
    trade_offs_json TEXT NOT NULL,
    applicable_when_json TEXT NOT NULL,
    not_applicable_when_json TEXT NOT NULL,
    outcome TEXT,
    evidence_json TEXT NOT NULL,
    status TEXT NOT NULL,
    tags_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_decision_card_project_id
ON decision_card (project_id);

CREATE INDEX IF NOT EXISTS idx_decision_card_status
ON decision_card (status);

CREATE TABLE IF NOT EXISTS decision_reuse_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    query TEXT NOT NULL,
    project_id INTEGER,
    matched_decision_ids_json TEXT NOT NULL,
    advice TEXT NOT NULL,
    accepted INTEGER,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_decision_reuse_record_project_id
ON decision_reuse_record (project_id);
