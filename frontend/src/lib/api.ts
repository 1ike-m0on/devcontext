export type ApiEnvelope<T> = {
  success: boolean;
  data: T;
  message: string;
  errorCode: string | null;
};

export type Health = {
  service: string;
  status: string;
  llm?: {
    provider: string;
    model: string;
    status: string;
    keyConfigured: boolean;
    keyStatus: string;
    lastErrorType?: string | null;
  };
  llmProvider?: string;
  llmModel?: string;
  llmClient?: string;
  vectorProvider?: string;
  timestamp?: string;
};

export type LlmProviderStatus = {
  provider: string;
  model: string;
  status: string;
  keyRequired: boolean;
  keyConfigured: boolean;
  keyStatus: string;
};

export type PendingLlmSettings = {
  provider: string;
  model: string;
  status: string;
  keyConfigured: boolean;
  keyStatus: string;
  localConfigPath: string;
};

export type LlmSettings = {
  provider: string;
  model: string;
  status: string;
  keyStatus: string;
  keyConfigured: boolean;
  lastCallStatus: string;
  lastErrorType?: string | null;
  lastErrorMessage?: string | null;
  lastCallAt?: string | null;
  restartRequired: boolean;
  pending?: PendingLlmSettings | null;
  localConfigPath: string;
  supportedProviders: LlmProviderStatus[];
};

export type LlmSettingsUpdate = {
  provider: string;
  model: string;
  apiKey?: string | null;
  geminiApiKey?: string | null;
  deepseekApiKey?: string | null;
};

export type Project = {
  id: number;
  name: string;
  rootPath: string;
  language?: string;
  framework?: string;
  defaultBranch: string;
  createdAt?: string;
  updatedAt?: string;
};

export type ReviewIssue = {
  id: number;
  reviewId: number;
  severity: string;
  title: string;
  filePath?: string;
  lineNumber?: number;
  description: string;
  impact?: string;
  suggestion?: string;
  confidence?: string;
  status?: string;
};

export type ReviewRecord = {
  id: number;
  projectId: number;
  runId?: number;
  baseBranch?: string;
  compareBranch?: string;
  diffHash?: string;
  score?: number;
  summary?: string;
  reportPath?: string;
  createdAt?: string;
  outcomeSummary?: ReviewOutcomeSummary;
};

export type ReviewMemorySignal = {
  projectId: number;
  reviewId: number;
  issueId: number;
  signalType: "confirmed_issue_pattern" | "false_positive_pattern" | string;
  feedbackStatus: string;
  title: string;
  filePath?: string | null;
  lineNumber?: number | null;
  description?: string | null;
  impact?: string | null;
  suggestion?: string | null;
  note?: string | null;
  updatedAt?: string | null;
};

export type ReviewOutcomeSummary = {
  total: number;
  pending: number;
  accepted: number;
  fixed: number;
  falsePositive: number;
  rejected: number;
  ignored: number;
  positiveOutcome: number;
  negativeOutcome: number;
};

export type ReviewCreateResult = {
  reviewId: number;
  runId?: number;
  score?: number;
  summary?: string;
  reportPath?: string;
  diffTruncated?: boolean;
  reviewMemorySignals?: ReviewMemorySignal[];
};

export type GitReviewSource = {
  sourceType: string;
  label: string;
  description: string;
  available: boolean;
  recommended: boolean;
  baseRef?: string | null;
  compareRef?: string | null;
  currentBranch?: string | null;
  changedFileCount: number;
  changedFiles: string[];
  reason?: string | null;
  untrackedFileCount?: number;
  untrackedFiles?: string[];
  warning?: string | null;
};

export type ReviewDetail = {
  review: ReviewRecord & { status?: string };
  issues: ReviewIssue[];
  reviewMemorySignals?: ReviewMemorySignal[];
  outcomeSummary?: ReviewOutcomeSummary;
};

export type ContextDocumentStatus = {
  type: string;
  path: string;
  exists: boolean;
  generated: boolean;
  status: string;
  sourceCommit?: string;
  updatedAt?: string;
};

export type ContextQualityIssue = {
  severity: "info" | "warning" | "error" | string;
  documentType: string;
  path: string;
  title: string;
  message: string;
  suggestion: string;
};

export type ContextQualitySummary = {
  level: "high" | "medium" | "low" | string;
  score: number;
  existingDocuments: number;
  totalDocuments: number;
  missingCount: number;
  todoCount: number;
  issues: ContextQualityIssue[];
};

export type ProjectContextStatus = {
  projectId: number;
  documents: ContextDocumentStatus[];
  quality?: ContextQualitySummary;
};

export type ContextGenerationResult = {
  projectId: number;
  generatedFiles: string[];
  generatedSkippedFiles: string[];
  manualCreatedFiles: string[];
  manualSkippedFiles: string[];
  todos: string[];
};

export type DecisionCard = {
  id: number;
  projectId: number | null;
  title: string;
  scenario?: string;
  options?: string[];
  decision?: string;
  reasons?: string[];
  tradeOffs?: string[];
  applicableWhen?: string[];
  notApplicableWhen?: string[];
  outcome?: string;
  evidence?: Array<{ type?: string; ref?: string; summary?: string }>;
  status: string;
  tags: string[];
  embeddingStatus?: string;
  embeddingUpdatedAt?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type KnowledgeSource = {
  id: number;
  name: string;
  rootPath: string;
  sourceType: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type KnowledgeEvidenceType =
  | "GENERATED_DOC"
  | "MANUAL_DOC"
  | "CODE_MAP"
  | "SQL_SCHEMA"
  | "MAPPER"
  | "CONFIG"
  | "DEPLOYMENT"
  | "OBSERVABILITY"
  | "TEST"
  | "BENCHMARK"
  | "CI"
  | "API_CONTROLLER"
  | "SERVICE_CODE"
  | "QUEUE"
  | "CACHE"
  | "SECURITY";

export type EvidenceCoverageReport = {
  sourceId: number;
  documentsIndexed: number;
  chunksIndexed: number;
  coverage: Partial<Record<KnowledgeEvidenceType, number>>;
  warnings: string[];
};

export type KnowledgeIndexResult = {
  sourceId: number;
  documentsIndexed: number;
  chunksIndexed: number;
  coverageReport?: EvidenceCoverageReport | null;
};

export type KnowledgeQueryPlan = {
  originalQuery: string;
  rewrittenQuery: string;
  normalizedTerms: string[];
  requiredEvidenceTypes: KnowledgeEvidenceType[];
  preferredEvidenceTypes: KnowledgeEvidenceType[];
  forbiddenEvidenceTypes: KnowledgeEvidenceType[];
  answerMode: string;
  noAnswerPolicy: string;
};

export type DecisionSearchResult = {
  decision: DecisionCard;
  score: number;
  matchedTags?: string[];
  matchedTerms?: string[];
  tagScore?: number;
  keywordScore?: number;
  vectorScore?: number;
  matchReasons?: string[];
};

export type DecisionSearchResponse = {
  query: string;
  matches: DecisionSearchResult[];
};

export type DecisionReuseAdviceResult = {
  runId: number;
  reuseRecordId: number;
  query: string;
  matchedDecisions: DecisionSearchResult[];
  advice: string;
};

export type KnowledgeSearchResult = {
  chunkId: number;
  documentId: number;
  sourceId: number;
  sourceName: string;
  filePath: string;
  title: string;
  headingPath: string;
  content: string;
  keywordScore: number;
  vectorScore: number;
  fusedScore: number;
  evidenceTypes?: KnowledgeEvidenceType[];
};

export type KnowledgeSearchResponse = {
  retrievalRecordId: number;
  query: string;
  rewrittenQuery: string;
  queryPlan?: KnowledgeQueryPlan;
  results: KnowledgeSearchResult[];
};

export type RagAnswerResult = {
  runId: number;
  retrievalRecordId: number;
  query: string;
  rewrittenQuery: string;
  queryPlan?: KnowledgeQueryPlan;
  answer: string;
  citations: KnowledgeSearchResult[];
};

export type AgentRun = {
  id: number;
  projectId?: number | null;
  runType: string;
  status: string;
  modelName?: string;
  promptVersion?: string;
  inputTokenEstimate?: number;
  outputTokenEstimate?: number;
  durationMs?: number;
  errorMessage?: string;
  createdAt?: string;
  finishedAt?: string;
};

export type AgentEvent = {
  id: number;
  runId: number;
  eventType: string;
  inputSummary?: string;
  outputSummary?: string;
  status: string;
  durationMs?: number;
  errorMessage?: string;
  createdAt?: string;
};

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
    ...init,
  });
  const payload = (await response.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!response.ok || payload?.success === false) {
    throw new Error(payload?.message || `${response.status} ${response.statusText}`);
  }
  return payload?.data as T;
}

export const api = {
  health: () => request<Health>("/api/health"),
  llmSettings: () => request<LlmSettings>("/api/settings/llm"),
  updateLlmSettings: (body: LlmSettingsUpdate) =>
    request<LlmSettings>("/api/settings/llm", { method: "PUT", body: JSON.stringify(body) }),
  projects: () => request<Project[]>("/api/projects"),
  createProject: (body: { name: string; rootPath: string; defaultBranch: string }) =>
    request<Project>("/api/projects", { method: "POST", body: JSON.stringify(body) }),
  updateProject: (projectId: number, body: { name?: string; rootPath?: string; defaultBranch?: string }) =>
    request<Project>(`/api/projects/${projectId}`, { method: "PATCH", body: JSON.stringify(body) }),
  deleteProject: (projectId: number) =>
    request<{ projectId: number; deleted: boolean }>(`/api/projects/${projectId}`, { method: "DELETE" }),
  contextStatus: (projectId: number) => request<ProjectContextStatus>(`/api/projects/${projectId}/context`),
  generateContext: (projectId: number, body: { overwriteGenerated: boolean; overwriteManual: boolean }) =>
    request<ContextGenerationResult>(`/api/projects/${projectId}/context/generate`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  contextItems: (projectId: number) => request<unknown>(`/api/projects/${projectId}/context-items`),
  createReview: (
    projectId: number,
    body: {
      sourceType?: string | null;
      baseBranch?: string | null;
      compareBranch?: string | null;
      diffText?: string | null;
      mode: string;
      selectedFiles?: string[] | null;
    },
  ) => request<ReviewCreateResult>(`/api/projects/${projectId}/reviews`, { method: "POST", body: JSON.stringify(body) }),
  reviewSources: (projectId: number) => request<GitReviewSource[]>(`/api/projects/${projectId}/review-sources`),
  projectReviews: (projectId: number, limit = 20) => request<ReviewRecord[]>(`/api/projects/${projectId}/reviews?limit=${limit}`),
  review: (reviewId: number) => request<ReviewDetail>(`/api/reviews/${reviewId}`),
  updateReviewIssue: (issueId: number, body: { status: string; note?: string | null }) =>
    request<ReviewIssue>(`/api/review-issues/${issueId}`, { method: "PATCH", body: JSON.stringify(body) }),
  decisions: (params: URLSearchParams) => request<DecisionCard[]>(`/api/decisions?${params.toString()}`),
  decision: (decisionId: number) => request<DecisionCard>(`/api/decisions/${decisionId}`),
  createDecision: (body: {
    projectId: number | null;
    title: string;
    scenario: string;
    options: string[];
    decision: string;
    reasons: string[];
    tradeOffs: string[];
    applicableWhen: string[];
    notApplicableWhen: string[];
    outcome?: string | null;
    evidence: Array<{ type: string; ref: string; summary: string }>;
    status: string;
    tags: string[];
  }) => request<{ decisionId: number; status: string }>("/api/decisions", { method: "POST", body: JSON.stringify(body) }),
  updateDecisionStatus: (decisionId: number, status: string) =>
    request<unknown>(`/api/decisions/${decisionId}/status`, { method: "PATCH", body: JSON.stringify({ status }) }),
  searchDecisions: (body: { query: string; projectId: number | null; tags: string[]; topK: number }) =>
    request<DecisionSearchResponse>("/api/decisions/search", { method: "POST", body: JSON.stringify(body) }),
  reuseAdvice: (body: { query: string; projectId: number | null; tags: string[]; topK: number }) =>
    request<DecisionReuseAdviceResult>("/api/decisions/reuse-advice", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  updateReuseFeedback: (recordId: number, body: { status: string; accepted?: boolean | null; userFeedback?: string | null }) =>
    request<unknown>(`/api/decision-reuse-records/${recordId}/feedback`, { method: "PATCH", body: JSON.stringify(body) }),
  sources: () => request<KnowledgeSource[]>("/api/knowledge-sources"),
  createSource: (body: { name: string; rootPath: string; sourceType: string }) =>
    request<KnowledgeSource>("/api/knowledge-sources", { method: "POST", body: JSON.stringify(body) }),
  indexSource: (sourceId: number) => request<KnowledgeIndexResult>(`/api/knowledge-sources/${sourceId}/index`, { method: "POST" }),
  sourceCoverage: (sourceId: number) => request<EvidenceCoverageReport>(`/api/knowledge-sources/${sourceId}/coverage`),
  searchKnowledge: (body: { query: string; sourceId: number | null; topK: number }) =>
    request<KnowledgeSearchResponse>("/api/knowledge/search", { method: "POST", body: JSON.stringify(body) }),
  askKnowledge: (body: { query: string; sourceId: number | null; topK: number }) =>
    request<RagAnswerResult>("/api/knowledge/ask", { method: "POST", body: JSON.stringify(body) }),
  runs: (params: URLSearchParams) => request<AgentRun[]>(`/api/agent-runs?${params.toString()}`),
  run: (runId: number) => request<AgentRun>(`/api/agent-runs/${runId}`),
  runEvents: (runId: number) => request<AgentEvent[]>(`/api/agent-runs/${runId}/events`),
};
