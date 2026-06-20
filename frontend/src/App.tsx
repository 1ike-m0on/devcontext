import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  Activity,
  AlertTriangle,
  BookOpen,
  Boxes,
  Brain,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Database,
  FileSearch,
  FolderOpen,
  GitBranch,
  GitPullRequest,
  History,
  LayoutDashboard,
  Link2,
  Loader2,
  MapPin,
  MessageSquare,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Trash2,
  type LucideIcon,
} from "lucide-react";
import {
  api,
  AgentEvent,
  AgentRun,
  DecisionCard,
  DecisionReuseAdviceResult,
  DecisionSearchResponse,
  Health,
  GitReviewSource,
  EvidenceCoverageReport,
  EvidenceEvaluation,
  KnowledgeEvidenceType,
  KnowledgeIndexResult,
  KnowledgeQueryPlan,
  KnowledgeSearchResponse,
  KnowledgeSource,
  LlmConnectionCheckResult,
  LlmProviderStatus,
  LlmSettings,
  Project,
  ProjectContextStatus,
  ProjectEvidenceCoverageSummary,
  ContextDocumentStatus,
  ContextGenerationResult,
  ContextQualitySummary,
  RagAnswerResult,
  ReviewDetail,
  ReviewIssue,
  ReviewMemorySignal,
  ReviewOutcomeSummary,
  ReviewRecord,
} from "@/lib/api";
import { asNumberOrNull, cn, splitTags } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

type View = "overview" | "review" | "decisions" | "knowledge" | "runs" | "settings";

type OperationStatus = "idle" | "queued" | "running" | "completed" | "failed";
type AskStage = "idle" | "understanding" | "evidence" | "answering" | "completed" | "insufficient" | "error";

type SavedIndexState = {
  projectId: number;
  sourceId?: number;
  status: OperationStatus;
  startedAt?: string;
  completedAt?: string;
  failedAt?: string;
  documentsIndexed?: number;
  chunksIndexed?: number;
  error?: string;
};

function App() {
  const queryClient = useQueryClient();
  const [activeView, setActiveView] = useState<View>("overview");
  const [projectIdValue, setProjectIdValue] = useState(() => localStorage.getItem("devcontext.activeProjectId") ?? "");
  const [activeRunId, setActiveRunId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const activeProjectId = asNumberOrNull(projectIdValue);
  const healthQuery = useQuery({ queryKey: ["health"], queryFn: api.health, refetchInterval: 15000 });
  const llmSettingsQuery = useQuery({ queryKey: ["llm-settings"], queryFn: api.llmSettings });
  const projectsQuery = useQuery({ queryKey: ["projects"], queryFn: api.projects });
  const sourcesQuery = useQuery({ queryKey: ["knowledge-sources"], queryFn: api.sources });
  const decisionParams = useMemo(() => {
    const params = new URLSearchParams();
    params.set("status", "active");
    if (activeProjectId) params.set("projectId", String(activeProjectId));
    return params;
  }, [activeProjectId]);

  const decisionsQuery = useQuery({
    queryKey: ["decisions", decisionParams.toString()],
    queryFn: () => api.decisions(decisionParams),
  });

  const selectedProject = projectsQuery.data?.find((project) => project.id === activeProjectId) ?? null;

  function selectProject(nextProjectId: string) {
    setProjectIdValue(nextProjectId);
    if (nextProjectId) {
      localStorage.setItem("devcontext.activeProjectId", nextProjectId);
    } else {
      localStorage.removeItem("devcontext.activeProjectId");
    }
  }

  useEffect(() => {
    if (!projectIdValue || !projectsQuery.data) return;
    const stillExists = projectsQuery.data.some((project) => String(project.id) === projectIdValue);
    if (!stillExists) {
      clearProjectLocalState(Number(projectIdValue));
      selectProject("");
    }
  }, [projectIdValue, projectsQuery.data]);

  function show(message: string) {
    setNotice(message);
    window.setTimeout(() => setNotice(null), 4200);
  }

  function openRun(runId: number) {
    setActiveRunId(runId);
    setActiveView("runs");
  }

  function refresh() {
    void queryClient.invalidateQueries();
    show("已刷新工作台数据。");
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <div className="flex min-h-screen">
        <aside className="sticky top-0 hidden h-screen w-64 shrink-0 border-r border-border bg-card/60 p-4 lg:flex lg:flex-col">
          <BrandBlock />
          <nav className="mt-7 grid gap-1">
            <NavItem icon={LayoutDashboard} label="工作台" active={activeView === "overview"} onClick={() => setActiveView("overview")} />
            <NavItem icon={GitPullRequest} label="代码审查" active={activeView === "review"} onClick={() => setActiveView("review")} />
            <NavItem icon={Brain} label="决策记忆" active={activeView === "decisions"} onClick={() => setActiveView("decisions")} />
            <NavItem icon={Database} label="知识库" active={activeView === "knowledge"} onClick={() => setActiveView("knowledge")} />
            <NavItem icon={Activity} label="运行追踪" active={activeView === "runs"} onClick={() => setActiveView("runs")} />
            <NavItem icon={Settings2} label="模型设置" active={activeView === "settings"} onClick={() => setActiveView("settings")} />
          </nav>
          <div className="mt-auto grid gap-3">
            <HealthCard health={healthQuery.data} loading={healthQuery.isLoading} />
          </div>
        </aside>

        <main className="min-w-0 flex-1">
          <TopBar
            activeView={activeView}
            projects={projectsQuery.data ?? []}
            selectedProjectId={projectIdValue}
            onSelectProject={selectProject}
            onRefresh={refresh}
            onNotice={show}
          />

          {notice ? (
            <div className="mx-5 mt-4 rounded-md border border-primary/20 bg-primary/10 px-4 py-3 text-sm text-primary lg:mx-8">
              {notice}
            </div>
          ) : null}

          <div className="p-5 lg:p-8">
            {activeView === "overview" ? (
              <Overview
                project={selectedProject}
                decisions={decisionsQuery.data ?? []}
                sources={sourcesQuery.data ?? []}
                onGo={setActiveView}
                onNotice={show}
                onSelectProject={selectProject}
              />
            ) : null}
            {activeView === "review" ? (
              <ReviewWorkspace project={selectedProject} onNotice={show} onOpenRun={openRun} />
            ) : null}
            {activeView === "decisions" ? (
              <DecisionWorkspace
                project={selectedProject}
                decisions={decisionsQuery.data ?? []}
                onNotice={show}
                onOpenRun={openRun}
              />
            ) : null}
            {activeView === "knowledge" ? (
              <KnowledgeWorkspace
                project={selectedProject}
                sources={sourcesQuery.data ?? []}
                settings={llmSettingsQuery.data}
                onNotice={show}
                onOpenRun={openRun}
              />
            ) : null}
            {activeView === "runs" ? <RunWorkspace initialRunId={activeRunId} project={selectedProject} /> : null}
            {activeView === "settings" ? (
              <SettingsWorkspace
                settings={llmSettingsQuery.data}
                loading={llmSettingsQuery.isLoading}
                onNotice={show}
              />
            ) : null}
          </div>
        </main>
      </div>
    </div>
  );
}

function BrandBlock() {
  return (
    <div className="flex items-center gap-3">
      <div className="grid size-10 place-items-center rounded-md border border-border bg-background text-primary">
        <Boxes className="size-5" />
      </div>
      <div>
        <div className="font-semibold">DevContext</div>
        <div className="text-xs text-muted-foreground">AI 研发工作台</div>
      </div>
    </div>
  );
}

function TopBar({
  activeView,
  projects,
  selectedProjectId,
  onSelectProject,
  onRefresh,
  onNotice,
}: {
  activeView: View;
  projects: Project[];
  selectedProjectId: string;
  onSelectProject: (projectId: string) => void;
  onRefresh: () => void;
  onNotice: (message: string) => void;
}) {
  const titleMap: Record<View, string> = {
    overview: "项目工作台",
    review: "代码审查",
    decisions: "决策记忆",
    knowledge: "知识库问答",
    runs: "运行追踪",
    settings: "模型设置",
  };
  const selectedProject = projects.find((project) => String(project.id) === selectedProjectId) ?? null;

  return (
    <header className="border-b border-border bg-background/95 px-5 py-4 lg:px-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="text-xs font-medium text-muted-foreground">当前空间</div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">{titleMap[activeView]}</h1>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          {activeView === "overview" ? (
            <ProjectPicker
              projects={projects}
              selectedProjectId={selectedProjectId}
              onSelectProject={onSelectProject}
              onNotice={onNotice}
            />
          ) : (
            <div className="rounded-md border border-border bg-secondary px-3 py-2 text-sm text-secondary-foreground">
              {selectedProject ? selectedProject.name : "未选择项目"}
            </div>
          )}
          <Button size="icon" variant="secondary" onClick={onRefresh} aria-label="刷新">
            <RefreshCw className="size-4" />
          </Button>
        </div>
      </div>
    </header>
  );
}

function ProjectPicker({
  projects,
  selectedProjectId,
  onSelectProject,
  onNotice,
}: {
  projects: Project[];
  selectedProjectId: string;
  onSelectProject: (projectId: string) => void;
  onNotice: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const selectedProject = projects.find((project) => String(project.id) === selectedProjectId) ?? null;

  function select(projectId: string) {
    onSelectProject(projectId);
    setOpen(false);
  }

  return (
    <div className="relative w-[26rem] max-w-full">
      <div className="mb-1 text-xs font-medium text-muted-foreground">项目</div>
      <Button
        type="button"
        variant="secondary"
        className="h-auto min-h-11 w-full justify-between gap-3 px-3 py-2 text-left"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
      >
        <span className="flex min-w-0 items-center gap-3">
          <span className="grid size-8 shrink-0 place-items-center rounded-md border border-border bg-card text-primary-foreground">
            <FolderOpen className="size-4" />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-foreground">
              {selectedProject ? selectedProject.name : "未选择项目"}
            </span>
            <span className="mt-0.5 block truncate text-xs text-muted-foreground">
              {selectedProject ? pathSummary(selectedProject.rootPath) : "导入或选择一个项目"}
            </span>
          </span>
        </span>
        <ChevronDown className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-180")} />
      </Button>

      {open ? (
        <div className="absolute right-0 z-30 mt-2 w-[34rem] max-w-[calc(100vw-2rem)] rounded-lg border border-border bg-card p-2 shadow-lg">
          <div className="flex items-center justify-between gap-3 px-2 py-2">
            <div>
              <div className="text-sm font-medium">项目列表</div>
              <div className="text-xs text-muted-foreground">选择项目，或直接编辑和删除登记。</div>
            </div>
            <ImportProjectDialog onSelectProject={onSelectProject} onNotice={onNotice} label="导入项目" />
          </div>

          <button
            type="button"
            className={cn(
              "mt-1 flex w-full items-center gap-3 rounded-md border border-transparent px-3 py-2 text-left text-sm transition-colors hover:border-border hover:bg-secondary",
              !selectedProjectId && "border-primary/50 bg-primary/20",
            )}
            onClick={() => select("")}
          >
            <span className="grid size-8 place-items-center rounded-md border border-border bg-background text-muted-foreground">
              {!selectedProjectId ? <CheckCircle2 className="size-4" /> : <FolderOpen className="size-4" />}
            </span>
            <span>
              <span className="block font-medium">未选择项目</span>
              <span className="text-xs text-muted-foreground">回到准备流程起点</span>
            </span>
          </button>

          <div className="mt-2 max-h-80 overflow-auto pr-1">
            {projects.length ? (
              projects.map((project) => {
                const selected = String(project.id) === selectedProjectId;
                return (
                  <div
                    key={project.id}
                    className={cn(
                      "grid grid-cols-[minmax(0,1fr)_auto] items-center gap-2 rounded-md border border-transparent p-1 transition-colors",
                      selected ? "border-primary/50 bg-primary/20" : "hover:border-border hover:bg-secondary/60",
                    )}
                  >
                    <button
                      type="button"
                      className="flex min-w-0 items-center gap-3 rounded-md px-2 py-2 text-left"
                      onClick={() => select(String(project.id))}
                    >
                      <span className="grid size-8 shrink-0 place-items-center rounded-md border border-border bg-background text-primary-foreground">
                        {selected ? <CheckCircle2 className="size-4" /> : <FolderOpen className="size-4" />}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium text-foreground">{project.name}</span>
                        <span className="mt-0.5 block truncate font-mono text-xs text-muted-foreground">
                          {pathSummary(project.rootPath)} · {project.defaultBranch}
                        </span>
                      </span>
                    </button>
                    <div className="flex shrink-0 items-center gap-1 pr-1">
                      <ProjectSettingsDialog
                        project={project}
                        onNotice={onNotice}
                        onSelectProject={onSelectProject}
                        hideDelete
                        trigger={
                          <Button size="icon" variant="ghost" className="size-8" aria-label={`编辑 ${project.name}`}>
                            <Pencil className="size-4" />
                          </Button>
                        }
                      />
                      <ProjectDeleteButton
                        project={project}
                        selected={selected}
                        onSelectProject={onSelectProject}
                        onNotice={onNotice}
                      />
                    </div>
                  </div>
                );
              })
            ) : (
              <div className="rounded-md border border-dashed border-border bg-background p-4 text-sm text-muted-foreground">
                还没有项目。先导入项目，再建立索引。
              </div>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function Overview({
  project,
  sources,
  onGo,
  onNotice,
  onSelectProject,
}: {
  project: Project | null;
  decisions: DecisionCard[];
  sources: KnowledgeSource[];
  onGo: (view: View) => void;
  onNotice: (message: string) => void;
  onSelectProject: (projectId: string) => void;
}) {
  const queryClient = useQueryClient();
  const projectSource = useMemo(() => findKnowledgeSourceForProject(sources, project), [sources, project?.rootPath]);
  const [indexState, setIndexState] = useState<SavedIndexState | null>(() => readIndexState(project?.id ?? null));

  useEffect(() => {
    setIndexState(readIndexState(project?.id ?? null));
  }, [project?.id]);

  const resolvedIndexState = resolveIndexState(project, projectSource, indexState);
  const indexBusy = resolvedIndexState.status === "queued" || resolvedIndexState.status === "running";

  useEffect(() => {
    if (!project || !indexBusy) return;
    const timer = window.setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
    }, 3500);
    return () => window.clearInterval(timer);
  }, [indexBusy, project?.id, queryClient]);

  useEffect(() => {
    if (!project || !indexState || !projectSource || projectSource.status !== "indexed") return;
    if (indexState.status === "queued" || indexState.status === "running") {
      const next = {
        ...indexState,
        sourceId: projectSource.id,
        status: "completed" as OperationStatus,
        completedAt: projectSource.updatedAt ?? new Date().toISOString(),
      };
      setIndexState(next);
      writeIndexState(next);
    }
  }, [indexState, project, projectSource]);

  const indexKnowledge = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      const startedAt = new Date().toISOString();
      const queued: SavedIndexState = { projectId: project.id, sourceId: projectSource?.id, status: "queued", startedAt };
      setIndexState(queued);
      writeIndexState(queued);
      let source = projectSource;
      if (!source) {
        source = await api.createSource({
          name: `${project.name} Knowledge`,
          rootPath: project.rootPath,
          sourceType: "project_ai_docs",
        });
        void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
      }
      const running: SavedIndexState = { projectId: project.id, sourceId: source.id, status: "running", startedAt };
      setIndexState(running);
      writeIndexState(running);
      const result = await api.indexSource(source.id);
      return { result, sourceId: source.id, startedAt };
    },
    onSuccess: ({ result, sourceId }) => {
      if (!project) return;
      const completed: SavedIndexState = {
        projectId: project.id,
        sourceId,
        status: "completed",
        completedAt: new Date().toISOString(),
        documentsIndexed: result.documentsIndexed,
        chunksIndexed: result.chunksIndexed,
      };
      setIndexState(completed);
      writeIndexState(completed);
      void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
      onNotice(`索引完成：${result.documentsIndexed} 个文档，${result.chunksIndexed} 个片段。`);
    },
    onError: (error) => {
      if (!project) return;
      const failed: SavedIndexState = {
        projectId: project.id,
        sourceId: projectSource?.id,
        status: "failed",
        failedAt: new Date().toISOString(),
        error: error instanceof Error ? error.message : "索引失败。",
      };
      setIndexState(failed);
      writeIndexState(failed);
      onNotice(failed.error ?? "索引失败。");
    },
  });

  const canAsk = Boolean(project && resolvedIndexState.status === "completed");

  return (
    <div className="mx-auto grid w-full max-w-6xl gap-6">
      <section className="rounded-lg border border-border bg-card p-6">
        <div className="flex flex-wrap items-start justify-between gap-5">
          <div className="max-w-2xl">
            <div className="text-sm font-medium text-muted-foreground">开始使用</div>
            <h2 className="mt-2 text-2xl font-semibold tracking-tight">按顺序准备项目知识，然后开始提问</h2>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">
              首页只负责准备工作：选择项目、确认地址、刷新索引。提问时会自动完成问题理解、证据选择和回答生成。
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <ImportProjectDialog onSelectProject={onSelectProject} onNotice={onNotice} label="选择项目" />
            <Button disabled={!canAsk} onClick={() => onGo("knowledge")}>
              <Sparkles className="size-4" />
              开始提问
            </Button>
          </div>
        </div>
      </section>

      <section className="grid gap-3">
        <WorkflowStep
          icon={FolderOpen}
          title="选择项目"
          description={project ? project.rootPath : "选择一个本地项目后，后续操作会围绕它进行。"}
          status={project ? "completed" : "idle"}
          meta={project ? project.name : "未选择"}
          action={<ImportProjectDialog onSelectProject={onSelectProject} onNotice={onNotice} label={project ? "更换项目" : "选择项目"} />}
        />
        <WorkflowStep
          icon={MapPin}
          title="确认项目地址"
          description={project ? "地址只在首页管理；问答页和审查页只消费当前项目。" : "创建项目时填写名称并通过地址选择入口准备项目路径。"}
          status={project ? "completed" : "idle"}
          meta={project ? "可管理" : "未选择"}
          action={<ProjectSettingsDialog project={project} onNotice={onNotice} onSelectProject={onSelectProject} />}
        />
        <WorkflowStep
          icon={Database}
          title="建立/刷新索引"
          description={indexStatusText(resolvedIndexState, projectSource)}
          status={resolvedIndexState.status}
          meta={indexStatusMeta(resolvedIndexState, projectSource)}
          action={
            <Button disabled={!project || indexBusy || indexKnowledge.isPending} onClick={() => indexKnowledge.mutate()}>
              {indexBusy || indexKnowledge.isPending ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
              {resolvedIndexState.status === "completed" ? "刷新索引" : indexBusy ? "正在索引" : "建立索引"}
            </Button>
          }
        />
        <WorkflowStep
          icon={MessageSquare}
          title="提问并查看答案"
          description={canAsk ? "索引已准备好，问答页会展示 Markdown、引用路径和 no-answer 状态。" : "先完成索引，再进入问答。"}
          status={canAsk ? "completed" : "idle"}
          meta="Answer + Sources + Trace"
          action={
            <Button disabled={!canAsk} onClick={() => onGo("knowledge")}>
              <Sparkles className="size-4" />
              开始提问
            </Button>
          }
        />
        <WorkflowStep
          icon={Link2}
          title="查看引用和 evidence trace"
          description="回答页默认显示来源路径；更细的 evidence evaluation、provider/model 和 retrieval trace 默认折叠。"
          status={canAsk ? "completed" : "idle"}
          meta="Source paths"
          action={
            <Button variant="secondary" disabled={!canAsk} onClick={() => onGo("knowledge")}>
              <FileSearch className="size-4" />
              查看引用
            </Button>
          }
        />
      </section>
    </div>
  );
}

function WorkflowStep({
  icon: Icon,
  title,
  description,
  status,
  meta,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  status: OperationStatus;
  meta: string;
  action: ReactNode;
}) {
  const running = status === "queued" || status === "running";
  const completed = status === "completed";
  return (
    <div className="grid gap-4 rounded-lg border border-border bg-card p-5 md:grid-cols-[48px_minmax(0,1fr)_auto] md:items-center">
      <div
        className={cn(
          "grid size-11 place-items-center rounded-lg border",
          completed && "border-emerald-300/70 bg-emerald-50 text-emerald-800",
          running && "border-amber-300/70 bg-amber-50 text-amber-800",
          !completed && !running && "border-border bg-secondary text-muted-foreground",
        )}
      >
        <Icon className="size-5" />
      </div>
      <div className="min-w-0">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <h3 className="font-semibold">{title}</h3>
          <Badge variant={operationBadge(status)}>{operationLabel(status)}</Badge>
          <span className="text-xs text-muted-foreground">{meta}</span>
        </div>
        <p className="mt-1 break-words text-sm leading-6 text-muted-foreground">{description}</p>
      </div>
      <div className="flex md:justify-end">{action}</div>
    </div>
  );
}

function ProjectSettingsDialog({
  project,
  onNotice,
  onSelectProject,
  trigger,
  hideDelete = false,
}: {
  project: Project | null;
  onNotice: (message: string) => void;
  onSelectProject: (projectId: string) => void;
  trigger?: ReactNode;
  hideDelete?: boolean;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const directoryInputRef = useRef<HTMLInputElement | null>(null);
  const [form, setForm] = useState({ name: "", rootPath: "", defaultBranch: "main" });
  const [browserPickNote, setBrowserPickNote] = useState<string | null>(null);

  useEffect(() => {
    setForm({
      name: project?.name ?? "",
      rootPath: project?.rootPath ?? "",
      defaultBranch: project?.defaultBranch ?? "main",
    });
    setBrowserPickNote(null);
  }, [project?.id, project?.name, project?.rootPath, project?.defaultBranch]);

  const updateProject = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      return api.updateProject(project.id, form);
    },
    onSuccess: (updated) => {
      void queryClient.invalidateQueries({ queryKey: ["projects"] });
      onSelectProject(String(updated.id));
      setOpen(false);
      onNotice("项目信息已更新。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "项目更新失败。"),
  });

  const deleteProject = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      const confirmed = window.confirm(`删除项目登记“${project.name}”？本地文件不会被删除。`);
      if (!confirmed) throw new Error("已取消删除。");
      return api.deleteProject(project.id);
    },
    onSuccess: () => {
      if (project) clearProjectLocalState(project.id);
      onSelectProject("");
      void queryClient.invalidateQueries({ queryKey: ["projects"] });
      void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
      setOpen(false);
      onNotice("项目登记已移除，本地项目文件不会被删除。");
    },
    onError: (error) => {
      const message = error instanceof Error ? error.message : "项目删除失败。";
      if (message !== "已取消删除。") onNotice(message);
    },
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        {trigger ?? (
          <Button variant="secondary" disabled={!project}>
            <Settings2 className="size-4" />
            项目设置
          </Button>
        )}
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>项目设置</DialogTitle>
          <DialogDescription>修改 DevContext 中的项目登记，不直接修改本地项目文件。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <Label>名称<Input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></Label>
          <div className="grid gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="secondary" onClick={() => directoryInputRef.current?.click()}>
                <FileSearch className="size-4" />
                选择地址
              </Button>
              <span className="text-xs text-muted-foreground">当前地址保留为已登记路径；浏览器选择不会暴露真实绝对路径。</span>
            </div>
            <input
              ref={directoryInputRef}
              type="file"
              className="hidden"
              multiple
              {...{ webkitdirectory: "", directory: "" }}
              onChange={(event) => {
                const file = event.target.files?.[0];
                const relative = file?.webkitRelativePath || file?.name || "";
                const folderName = relative.split("/")[0] || "已选择地址";
                setForm((current) => ({ ...current, name: current.name || folderName }));
                setBrowserPickNote(`浏览器只提供目录名“${folderName}”，不会暴露真实绝对路径；因此本轮不会伪造地址。`);
              }}
            />
            <div className="rounded-md border border-border bg-muted p-3 text-xs text-muted-foreground">
              <div className="font-medium text-foreground">已登记地址</div>
              <div className="mt-1 break-all font-mono">{form.rootPath || "未登记"}</div>
            </div>
            {browserPickNote ? <InlineState tone="empty" text={browserPickNote} /> : null}
          </div>
          <div className="rounded-md border border-red-300/40 bg-red-50 p-3 text-sm leading-6 text-red-800">
            删除项目只会移除工作台登记和本页保存的最近索引状态，不会删除磁盘目录。
          </div>
        </div>
        <DialogFooter>
          {!hideDelete ? (
            <Button
              variant="ghost"
              className="text-red-700 hover:bg-red-50 hover:text-red-800"
              disabled={!project || deleteProject.isPending}
              onClick={() => deleteProject.mutate()}
            >
              {deleteProject.isPending ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
              删除登记
            </Button>
          ) : null}
          <DialogClose asChild><Button variant="ghost">取消</Button></DialogClose>
          <Button onClick={() => updateProject.mutate()} disabled={!project || updateProject.isPending}>
            {updateProject.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            保存
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ProjectDeleteButton({
  project,
  selected,
  onSelectProject,
  onNotice,
}: {
  project: Project;
  selected: boolean;
  onSelectProject: (projectId: string) => void;
  onNotice: (message: string) => void;
}) {
  const queryClient = useQueryClient();
  const deleteProject = useMutation({
    mutationFn: async () => {
      const confirmed = window.confirm(`删除项目登记“${project.name}”？本地文件不会被删除。`);
      if (!confirmed) throw new Error("已取消删除。");
      return api.deleteProject(project.id);
    },
    onSuccess: () => {
      clearProjectLocalState(project.id);
      if (selected) onSelectProject("");
      void queryClient.invalidateQueries({ queryKey: ["projects"] });
      void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
      onNotice("项目登记已删除，本地项目文件不会被删除。");
    },
    onError: (error) => {
      const message = error instanceof Error ? error.message : "项目删除失败。";
      if (message !== "已取消删除。") onNotice(message);
    },
  });

  return (
    <Button
      type="button"
      size="icon"
      variant="destructive"
      className="size-8"
      disabled={deleteProject.isPending}
      aria-label={`删除 ${project.name}`}
      onClick={() => deleteProject.mutate()}
    >
      {deleteProject.isPending ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
    </Button>
  );
}

function ContextGenerationPanel({ result }: { result: ContextGenerationResult | null }) {
  if (!result) {
    return null;
  }
  const written = result.generatedFiles.length + result.manualCreatedFiles.length;
  const skipped = result.generatedSkippedFiles.length + result.manualSkippedFiles.length;
  return (
    <section className="rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h3 className="font-semibold">本次上下文生成结果</h3>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">
            Project #{result.projectId} · 写入 {written} 个 · 跳过 {skipped} 个 · TODO {result.todos.length} 条
          </p>
        </div>
      </div>
      <div className="mt-5 grid grid-cols-2 gap-4 max-xl:grid-cols-1">
        <GenerationList title="生成资产已写入" items={result.generatedFiles} empty="没有生成资产被写入。" />
        <GenerationList title="生成资产已跳过" items={result.generatedSkippedFiles} empty="没有生成资产被跳过。" />
        <GenerationList title="人工文档已创建" items={result.manualCreatedFiles} empty="没有新建人工文档。" />
        <GenerationList title="人工文档已保护" items={result.manualSkippedFiles} empty="没有人工文档被跳过。" />
      </div>
      {result.todos.length ? (
        <div className="mt-4 rounded-md border border-amber-400/20 bg-background p-4">
          <div className="text-sm font-medium">待补充事项</div>
          <ul className="mt-2 grid gap-2 text-sm leading-6 text-muted-foreground">
            {result.todos.map((todo) => (
              <li key={todo}>- {todo}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

function GenerationList({ title, items, empty }: { title: string; items: string[]; empty: string }) {
  return (
    <div className="min-w-0 rounded-md border border-border bg-background p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="font-medium">{title}</div>
        <Badge variant={items.length ? "success" : "secondary"}>{items.length}</Badge>
      </div>
      {items.length ? (
        <div className="mt-3 grid max-h-44 gap-2 overflow-auto pr-1">
          {items.map((item) => (
            <div key={item} className="break-all font-mono text-xs text-muted-foreground">{item}</div>
          ))}
        </div>
      ) : (
        <p className="mt-3 text-sm text-muted-foreground">{empty}</p>
      )}
    </div>
  );
}

function EvidenceCoveragePanel({ coverage, loading }: { coverage?: ProjectEvidenceCoverageSummary; loading: boolean }) {
  const primaryGroups = primaryEvidenceGroups(coverage);
  const stats = primaryEvidenceStats(coverage);
  const primaryGroupKeys = new Set(primaryGroups.map((group) => group.groupKey));
  const primaryMissing = (coverage?.missingCategories ?? []).filter((item) => primaryGroupKeys.has(item.category));
  const missingByCategory = new Map((coverage?.missingCategories ?? []).map((item) => [item.category, item]));
  const skipped = coverage?.skippedCategories ?? [];

  if (loading) {
    return (
      <section className="rounded-lg border border-border bg-card p-5">
        <div className="flex items-start gap-3">
          <Loader2 className="mt-0.5 size-5 animate-spin text-primary" />
          <div>
            <h3 className="font-semibold">原始证据覆盖</h3>
            <p className="mt-1 text-sm leading-6 text-muted-foreground">正在扫描 source / config / SQL / test / report 覆盖。</p>
          </div>
        </div>
      </section>
    );
  }

  if (!coverage) {
    return (
      <section className="rounded-lg border border-border bg-card p-5">
        <div className="flex items-start gap-3">
          <FileSearch className="mt-0.5 size-5 text-muted-foreground" />
          <div>
            <h3 className="font-semibold">原始证据覆盖</h3>
            <p className="mt-1 text-sm leading-6 text-muted-foreground">选择项目后，这里会优先展示源码、配置、SQL、测试和报告覆盖。</p>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          <div className={cn("grid size-10 shrink-0 place-items-center rounded-md border", stats.missing ? "border-amber-400/25 bg-amber-400/10 text-amber-800" : "border-emerald-400/25 bg-emerald-400/10 text-emerald-800")}>
            <Database className="size-5" />
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="font-semibold">原始证据覆盖</h3>
              <Badge variant={stats.missing ? "warning" : "success"}>{stats.present}/{stats.total} 类主证据</Badge>
            </div>
            <p className="mt-1 max-w-3xl text-sm leading-6 text-muted-foreground">
              主信号来自源码、配置、SQL/schema/mapper、测试和 benchmark/review/runtime report；生成文档和人工文档只作为辅助资产。
            </p>
          </div>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center text-xs max-sm:w-full">
          <div className="rounded-md bg-background px-3 py-2">
            <div className="text-muted-foreground">primary</div>
            <div className="mt-1 text-base font-semibold">{coverage.primaryEvidenceCount}</div>
          </div>
          <div className="rounded-md bg-background px-3 py-2">
            <div className="text-muted-foreground">secondary</div>
            <div className="mt-1 text-base font-semibold">{coverage.secondaryEvidenceCount}</div>
          </div>
          <div className="rounded-md bg-background px-3 py-2">
            <div className="text-muted-foreground">derived</div>
            <div className="mt-1 text-base font-semibold">{coverage.derivedEvidenceCount}</div>
          </div>
        </div>
      </div>

      <div className="mt-5 grid gap-3">
        {primaryGroups.map((group) => {
          const missing = missingByCategory.get(group.groupKey);
          return (
            <div key={group.groupKey} className="rounded-md border border-border bg-background p-4">
              <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <span className="font-medium">{sourceGroupLabel(group.groupKey, group.label)}</span>
                    <Badge variant={group.present ? "success" : "warning"}>{group.present ? `${group.count} evidence` : "missing"}</Badge>
                    {group.sourceReliabilities.map((reliability) => (
                      <Badge key={reliability} variant={reliability === "primary" ? "success" : "secondary"}>{sourceReliabilityLabel(reliability)}</Badge>
                    ))}
                  </div>
                  <div className="mt-2 flex min-w-0 flex-wrap gap-2">
                    {group.sourceKinds.map((kind) => (
                      <Badge key={kind} variant="secondary">{sourceKindLabel(kind)}</Badge>
                    ))}
                    {group.evidenceTypes.map((type) => (
                      <Badge key={type} variant="secondary">{evidenceTypeLabel(type)}</Badge>
                    ))}
                  </div>
                </div>
              </div>
              {group.samplePaths.length ? (
                <div className="mt-3 grid gap-1">
                  {group.samplePaths.slice(0, 3).map((path) => (
                    <div key={path} className="min-w-0 truncate font-mono text-xs text-muted-foreground">{path}</div>
                  ))}
                </div>
              ) : (
                <p className="mt-3 text-sm text-muted-foreground">{missing?.reason ?? "还没有发现这类原始证据。"}</p>
              )}
            </div>
          );
        })}
      </div>

      {skipped.length || primaryMissing.length ? (
        <div className="mt-5 grid grid-cols-2 gap-3 max-lg:grid-cols-1">
          <EvidenceCategoryList title="主证据缺口" items={primaryMissing} variant="warning" />
          <EvidenceCategoryList title="跳过" items={skipped} variant="secondary" />
        </div>
      ) : null}
    </section>
  );
}

function EvidenceCategoryList({
  title,
  items,
  variant,
}: {
  title: string;
  items: ProjectEvidenceCoverageSummary["missingCategories"];
  variant: BadgeVariant;
}) {
  if (!items.length) {
    return (
      <div className="rounded-md border border-border bg-background p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="font-medium">{title}</div>
          <Badge variant="success">0</Badge>
        </div>
        <p className="mt-3 text-sm text-muted-foreground">暂无记录。</p>
      </div>
    );
  }

  return (
    <div className="rounded-md border border-border bg-background p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="font-medium">{title}</div>
        <Badge variant={variant}>{items.length}</Badge>
      </div>
      <div className="mt-3 grid max-h-48 gap-3 overflow-auto pr-1">
        {items.slice(0, 6).map((item) => (
          <div key={item.category} className="text-sm">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <Badge variant={variant}>{sourceGroupLabel(item.category, item.category)}</Badge>
              <span className="text-muted-foreground">{item.count}</span>
            </div>
            <p className="mt-1 leading-5 text-muted-foreground">{item.reason}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function SupportingDocumentQualityPanel({ quality, documents }: { quality?: ContextQualitySummary; documents: ContextDocumentStatus[] }) {
  const existingDocuments = documents.filter((document) => document.exists).length;
  if (!quality) {
    return (
      <section className="rounded-lg border border-border bg-card p-5">
        <div className="flex items-start gap-3">
          <FileSearch className="mt-0.5 size-5 text-muted-foreground" />
          <div>
            <h3 className="font-semibold">辅助文档完整度</h3>
            <p className="mt-1 text-sm leading-6 text-muted-foreground">这里会显示 `.ai` 生成/人工文档是否可作为辅助说明。</p>
          </div>
        </div>
      </section>
    );
  }

  const meta = qualityMeta(quality.level);
  const Icon = meta.icon;
  const issues = quality.issues.slice(0, 5);
  return (
    <section className="rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          <div className={cn("grid size-10 shrink-0 place-items-center rounded-md border", meta.iconClass)}>
            <Icon className="size-5" />
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="font-semibold">辅助文档完整度</h3>
              <Badge variant={meta.badge}>{meta.label}</Badge>
            </div>
            <p className="mt-1 max-w-3xl text-sm leading-6 text-muted-foreground">
              {meta.description}
            </p>
          </div>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center text-xs max-sm:w-full">
          <div className="rounded-md bg-background px-3 py-2">
            <div className="text-muted-foreground">缺失</div>
            <div className="mt-1 text-base font-semibold">{quality.missingCount}</div>
          </div>
          <div className="rounded-md bg-background px-3 py-2">
            <div className="text-muted-foreground">TODO</div>
            <div className="mt-1 text-base font-semibold">{quality.todoCount}</div>
          </div>
          <div className="rounded-md bg-background px-3 py-2">
            <div className="text-muted-foreground">辅助文档</div>
            <div className="mt-1 text-base font-semibold">{existingDocuments}/{documents.length}</div>
          </div>
        </div>
      </div>

      {issues.length ? (
        <div className="mt-5 grid gap-3">
          {issues.map((issue) => (
            <div key={`${issue.path}-${issue.title}`} className="grid grid-cols-[120px_minmax(0,1fr)] gap-3 rounded-md border border-border bg-background p-3 text-sm max-md:grid-cols-1">
              <div className="flex flex-wrap items-start gap-2">
                <Badge variant={qualityIssueBadge(issue.severity)}>{qualityIssueLabel(issue.severity)}</Badge>
                <span className="font-mono text-xs text-muted-foreground">{issue.documentType}</span>
              </div>
              <div className="min-w-0">
                <div className="font-medium">{issue.title}</div>
                <p className="mt-1 leading-6 text-muted-foreground">{issue.message}</p>
                <p className="mt-2 break-all font-mono text-xs text-muted-foreground">{issue.path}</p>
                <p className="mt-2 text-sm text-foreground">{issue.suggestion}</p>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="mt-5 rounded-md border border-emerald-400/20 bg-emerald-400/10 p-3 text-sm text-emerald-800">
          当前辅助文档没有发现缺失或 TODO，可作为原始证据之外的概览和人工备注。
        </div>
      )}
    </section>
  );
}

function SupportingDocumentAssetsPanel({
  documents,
  quality,
  hasContextStatus,
}: {
  documents: ContextDocumentStatus[];
  quality?: ContextQualitySummary;
  hasContextStatus: boolean;
}) {
  return (
    <section className="rounded-lg border border-border bg-card">
      <div className="flex items-center justify-between border-b border-border p-4">
        <div>
          <h3 className="font-semibold">辅助文档资产</h3>
          <p className="mt-1 text-sm text-muted-foreground">生成/人工文档、`.ai` 资产和代码地图用于概览、备注和导航，不作为主证据覆盖信号。</p>
        </div>
      </div>
      <div className="grid divide-y divide-border">
        {!hasContextStatus ? (
          <EmptyState text="选择项目后可以查看辅助文档状态。" />
        ) : documents.length ? (
          documents.map((doc) => (
            <div key={doc.path} className="grid grid-cols-[160px_minmax(0,1fr)_160px] gap-4 px-4 py-3 text-sm max-md:grid-cols-1">
              <span className="font-medium">{doc.type}</span>
              <span className="min-w-0 truncate font-mono text-xs text-muted-foreground">{doc.path}</span>
              <div className="flex flex-wrap justify-end gap-2 max-md:justify-start">
                <Badge variant={doc.generated ? "secondary" : "default"}>{doc.generated ? "generated" : "manual"}</Badge>
                <Badge variant={doc.exists ? "success" : "warning"}>{doc.exists ? doc.status : "missing"}</Badge>
                {quality?.issues.some((issue) => issue.path === doc.path) ? <Badge variant="warning">待确认</Badge> : null}
              </div>
            </div>
          ))
        ) : (
          <EmptyState text="没有可展示的辅助文档资产。" />
        )}
      </div>
    </section>
  );
}

function ReviewWorkspace({
  project,
  onNotice,
  onOpenRun,
}: {
  project: Project | null;
  onNotice: (message: string) => void;
  onOpenRun: (runId: number) => void;
}) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    sourceType: "auto",
    baseBranch: project?.defaultBranch ?? "main",
    compareBranch: "",
    diffText: "",
    mode: "strict",
    selectedFiles: [] as string[],
  });
  const [reviewId, setReviewId] = useState("");
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [focusedReviewIssueId, setFocusedReviewIssueId] = useState<number | null>(null);

  const reviewHistory = useQuery({
    queryKey: ["project-reviews", project?.id],
    queryFn: () => api.projectReviews(project?.id as number, 30),
    enabled: Boolean(project?.id),
  });
  const reviewSources = useQuery({
    queryKey: ["review-sources", project?.id],
    queryFn: () => api.reviewSources(project?.id as number),
    enabled: Boolean(project?.id),
  });
  const reviewMemoryInventory = useQuery({
    queryKey: ["project-review-memory-signals", project?.id],
    queryFn: () => api.projectReviewMemorySignals(project?.id as number, 20),
    enabled: Boolean(project?.id),
  });

  useEffect(() => {
    setForm((current) => ({
      ...current,
      baseBranch: project?.defaultBranch ?? "main",
      selectedFiles: [],
    }));
  }, [project?.id, project?.defaultBranch]);

  useEffect(() => {
    const recommended = reviewSources.data?.find((source) => source.available && source.recommended);
    if (recommended) {
      setForm((current) => ({ ...current, sourceType: recommended.sourceType, selectedFiles: [] }));
    }
  }, [reviewSources.data]);

  const selectedReviewSource = reviewSources.data?.find((source) => source.sourceType === form.sourceType) ?? null;

  const createReview = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      return api.createReview(project.id, {
        sourceType: form.sourceType,
        baseBranch: form.baseBranch || null,
        compareBranch: form.compareBranch || null,
        diffText: form.diffText || null,
        mode: form.mode,
        selectedFiles: form.selectedFiles.length ? form.selectedFiles : null,
      });
    },
    onMutate: () => {
      setFocusedReviewIssueId(null);
    },
    onSuccess: async (result) => {
      const next = await api.review(result.reviewId);
      setReviewId(String(result.reviewId));
      setDetail(next);
      setFocusedReviewIssueId(null);
      void queryClient.invalidateQueries({ queryKey: ["project-reviews", project?.id] });
      onNotice(result.diffTruncated ? `审查完成，解析出 ${next.issues.length} 个问题。注意：diff 已截断，请查看追踪确认范围。` : `审查完成，解析出 ${next.issues.length} 个问题。`);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "代码审查失败。"),
  });

  const updateIssue = useMutation({
    mutationFn: ({ issueId, status, note }: { issueId: number; status: string; note?: string | null }) =>
      api.updateReviewIssue(issueId, { status, note }),
    onSuccess: (updated) => {
      const nextDetail = detail ? withUpdatedReviewIssue(detail, updated) : null;
      if (nextDetail) {
        setDetail(nextDetail);
        if (project?.id) {
          queryClient.setQueryData<ReviewRecord[]>(["project-reviews", project.id], (records) =>
            records?.map((record) =>
              record.id === nextDetail.review.id
                ? { ...record, outcomeSummary: nextDetail.outcomeSummary }
                : record,
            ),
          );
          void queryClient.invalidateQueries({ queryKey: ["project-reviews", project.id] });
          void queryClient.invalidateQueries({ queryKey: ["project-review-memory-signals", project.id] });
        }
      }
      onNotice("问题状态已更新。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "状态更新失败。"),
  });

  async function loadReview() {
    const id = asNumberOrNull(reviewId);
    if (!id) {
      onNotice("请填写 Review ID。");
      return;
    }
    setFocusedReviewIssueId(null);
    const next = await api.review(id);
    setReviewId(String(id));
    setDetail(next);
  }

  async function loadReviewById(id: number) {
    setFocusedReviewIssueId(null);
    const next = await api.review(id);
    setDetail(next);
    setReviewId(String(id));
    return next;
  }

  async function openReviewMemorySource(reviewId: number, issueId: number) {
    try {
      const next = await loadReviewById(reviewId);
      if (next.issues.some((issue) => issue.id === issueId)) {
        setFocusedReviewIssueId(issueId);
      } else {
        onNotice("已打开来源 Review，但没有找到来源 Issue。");
      }
    } catch (error) {
      onNotice(error instanceof Error ? error.message : "来源 Review 加载失败。");
    }
  }

  return (
    <div className="grid min-w-0 grid-cols-1 gap-5 2xl:grid-cols-[minmax(360px,0.72fr)_minmax(620px,1.28fr)]">
      <section className="grid min-w-0 gap-5 overflow-hidden">
        <Card className="min-w-0 overflow-hidden">
          <CardHeader>
            <div>
              <CardTitle>创建审查</CardTitle>
              <CardDescription>{project ? "DevContext 会自动读取 Git 状态，优先推荐最适合的审查入口。" : "先在顶部选择项目。"}</CardDescription>
            </div>
            {project ? (
              <Button variant="secondary" onClick={() => reviewSources.refetch()} disabled={reviewSources.isFetching}>
                {reviewSources.isFetching ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
                刷新 Git 状态
              </Button>
            ) : null}
          </CardHeader>
          <CardContent>
            <form
              className="grid gap-4"
              onSubmit={(event) => {
                event.preventDefault();
                createReview.mutate();
              }}
            >
              <ReviewSourcePicker
                sources={reviewSources.data ?? []}
                selected={form.sourceType}
                loading={reviewSources.isLoading}
                error={reviewSources.isError}
                onSelect={(sourceType) => setForm({
                  ...form,
                  sourceType,
                  diffText: sourceType === "manual" ? form.diffText : "",
                  selectedFiles: [],
                })}
              />
              <ReviewFileSelector
                source={selectedReviewSource}
                selectedFiles={form.selectedFiles}
                onChange={(selectedFiles) => setForm({ ...form, selectedFiles })}
              />
              <Label>
                模式
                <select
                  className="h-9 rounded-md border border-input bg-background px-3 text-sm"
                  value={form.mode}
                  onChange={(event) => setForm({ ...form, mode: event.target.value })}
                >
                  <option value="strict">strict</option>
                  <option value="balanced">balanced</option>
                </select>
              </Label>
              <div className="rounded-lg border border-border bg-background p-4">
                <div className="mb-3 flex items-center gap-2 text-sm font-medium">
                  <Settings2 className="size-4 text-muted-foreground" />
                  高级审查来源
                </div>
                <div className="grid grid-cols-2 gap-3 max-md:grid-cols-1">
                  <Label>
                    基准分支
                    <Input value={form.baseBranch} onChange={(event) => setForm({ ...form, baseBranch: event.target.value })} />
                  </Label>
                  <Label>
                    对比分支
                    <Input
                      value={form.compareBranch}
                      onChange={(event) => setForm({ ...form, compareBranch: event.target.value })}
                      placeholder="feature/xxx"
                    />
                  </Label>
                </div>
                <Label className="mt-3 block">
                  手动 Diff
                  <Textarea
                    className="min-h-32 font-mono text-xs"
                    value={form.diffText}
                    onChange={(event) => setForm({ ...form, sourceType: "manual", diffText: event.target.value, selectedFiles: [] })}
                    placeholder="粘贴 diff 后会自动使用手动模式。"
                  />
                </Label>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button disabled={!project || createReview.isPending}>
                  {createReview.isPending ? <Loader2 className="size-4 animate-spin" /> : <GitPullRequest className="size-4" />}
                  开始审查
                </Button>
                <Button type="button" variant="secondary" onClick={() => setForm({ ...form, sourceType: "current_branch", diffText: "", selectedFiles: [] })}>
                  审查当前分支
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        <Card className="min-w-0 overflow-hidden">
          <CardHeader>
            <div>
              <CardTitle>审查历史</CardTitle>
              <CardDescription>{project ? `最近 ${reviewHistory.data?.length ?? 0} 次记录，点击即可回看。` : "先选择项目后查看历史。"}</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="grid gap-3">
            <div className="flex gap-2">
              <Input value={reviewId} onChange={(event) => setReviewId(event.target.value)} placeholder="Review ID" />
              <Button variant="secondary" onClick={() => void loadReview()}>
                加载
              </Button>
            </div>
            {!project ? (
              <EmptyState text="选择项目后会显示审查历史。" />
            ) : reviewHistory.isLoading ? (
              <EmptyState text="正在加载审查历史。" />
            ) : reviewHistory.isError ? (
              <EmptyState text="审查历史加载失败。请确认后端已重启到最新版本。" />
            ) : reviewHistory.data?.length ? (
              <div className="grid max-h-[420px] gap-2 overflow-auto pr-1">
                {reviewHistory.data.map((record) => (
                  <ReviewHistoryItem
                    key={record.id}
                    record={record}
                    active={detail?.review.id === record.id}
                    onOpen={() => {
                      setReviewId(String(record.id));
                      setFocusedReviewIssueId(null);
                      void loadReviewById(record.id);
                    }}
                  />
                ))}
              </div>
            ) : (
              <EmptyState text="还没有审查记录，完成一次审查后会出现在这里。" />
            )}
          </CardContent>
        </Card>
        <ReviewMemoryInventoryPanel
          project={project}
          signals={reviewMemoryInventory.data}
          loading={reviewMemoryInventory.isLoading}
          fetching={reviewMemoryInventory.isFetching}
          error={reviewMemoryInventory.isError}
          onRefresh={() => void reviewMemoryInventory.refetch()}
          onOpenReview={(reviewId, issueId) => void openReviewMemorySource(reviewId, issueId)}
        />
      </section>

      <section className="grid min-w-0 gap-5 overflow-hidden">
        <ReviewResultPanel
          detail={detail}
          focusedIssueId={focusedReviewIssueId}
          onOpenRun={onOpenRun}
          onUpdateIssue={(issueId, status, note) => updateIssue.mutate({ issueId, status, note })}
        />
        <Card className="min-w-0 overflow-hidden">
          <CardHeader>
            <CardTitle>结果处理</CardTitle>
            <CardDescription>审查结果不是终点，采纳、误报和已修复状态会沉淀为后续质量闭环。</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-3 gap-3 max-lg:grid-cols-1">
            <MiniProcess icon={Search} title="定位问题" text="查看文件、行号、描述、影响和建议。" />
            <MiniProcess icon={CheckCircle2} title="确认状态" text="标记 accepted、false_positive 或 fixed。" />
            <MiniProcess icon={Activity} title="追踪证据" text="需要排错时再查看 AgentRun 事件流。" />
          </CardContent>
        </Card>
      </section>
    </div>
  );

}

function ReviewSourcePicker({
  sources,
  selected,
  loading,
  error,
  onSelect,
}: {
  sources: GitReviewSource[];
  selected: string;
  loading: boolean;
  error: boolean;
  onSelect: (sourceType: string) => void;
}) {
  const fallbackSources: GitReviewSource[] = sources.length
    ? sources
    : [
        {
          sourceType: "auto",
          label: "自动选择",
          description: "后端会优先选择当前工作区、当前分支或最近提交中最合适的来源。",
          available: true,
          recommended: true,
          changedFileCount: 0,
          changedFiles: [],
          untrackedFileCount: 0,
          untrackedFiles: [],
        },
      ];

  if (loading) {
    return <EmptyState text="正在读取 Git 状态。" />;
  }

  if (error) {
    return (
      <div className="rounded-lg border border-amber-400/30 bg-amber-400/10 p-4 text-sm leading-6 text-amber-800">
        Git 状态读取失败。仍可在高级审查来源中手动填写分支或粘贴 diff。
      </div>
    );
  }

  return (
    <div className="grid min-w-0 gap-3">
      <div className="flex min-w-0 items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm font-medium">审查来源</div>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">优先选择推荐来源；手动分支和 diff 作为高级兜底。</p>
        </div>
        <Badge variant="secondary">{selected === "manual" ? "manual" : selected}</Badge>
      </div>
      <div className="grid min-w-0 gap-3">
        {fallbackSources.map((source) => (
          <button
            key={source.sourceType}
            type="button"
            disabled={!source.available}
            onClick={() => onSelect(source.sourceType)}
            className={cn(
              "min-w-0 overflow-hidden rounded-lg border border-border bg-background p-4 text-left transition-colors hover:border-primary/50 hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-60",
              selected === source.sourceType && "border-primary/70 bg-primary/10",
            )}
          >
            <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
              <div className="flex min-w-0 flex-1 gap-3">
                <div className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-md border border-border bg-card">
                  {reviewSourceIcon(source.sourceType)}
                </div>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{source.label}</span>
                    {source.recommended ? <Badge variant="success">推荐</Badge> : null}
                    {!source.available ? <Badge variant="warning">不可用</Badge> : null}
                    {source.untrackedFileCount ? <Badge variant="warning">未跟踪 {source.untrackedFileCount}</Badge> : null}
                  </div>
                  <p className="mt-1 text-sm leading-6 text-muted-foreground">{source.description}</p>
                  <p className="mt-1 break-words text-xs text-muted-foreground">{source.reason}</p>
                  {source.warning ? (
                    <p className="mt-2 rounded-md border border-amber-400/30 bg-amber-400/10 px-2 py-1 text-xs leading-5 text-amber-800">
                      {source.warning}
                    </p>
                  ) : null}
                </div>
              </div>
              <div className="shrink-0 text-right">
                <div className="text-lg font-semibold">{source.changedFileCount}</div>
                <div className="text-xs text-muted-foreground">files</div>
              </div>
            </div>
            {source.changedFiles?.length ? (
              <div className="mt-3 flex min-w-0 max-w-full flex-wrap gap-2 overflow-hidden">
                {source.changedFiles.slice(0, 6).map((file) => (
                  <Badge key={file} variant="secondary" className="min-w-0 max-w-full overflow-hidden font-mono">
                    <span className="block min-w-0 max-w-full truncate">{file}</span>
                  </Badge>
                ))}
                {source.changedFiles.length > 6 ? <Badge variant="secondary">+{source.changedFiles.length - 6}</Badge> : null}
              </div>
            ) : null}
          </button>
        ))}
        <button
          type="button"
          onClick={() => onSelect("manual")}
          className={cn(
            "min-w-0 overflow-hidden rounded-lg border border-dashed border-border bg-background p-4 text-left transition-colors hover:border-primary/50 hover:bg-secondary",
            selected === "manual" && "border-primary/70 bg-primary/10",
          )}
        >
          <div className="flex min-w-0 items-center gap-3">
            <FileSearch className="size-5 text-muted-foreground" />
            <div className="min-w-0">
              <div className="font-semibold">高级：手动分支或 Diff</div>
              <p className="mt-1 text-sm text-muted-foreground">适合审查指定分支、外部 diff 或 API 调试。</p>
            </div>
          </div>
        </button>
      </div>
    </div>
  );
}

function ReviewFileSelector({
  source,
  selectedFiles,
  onChange,
}: {
  source: GitReviewSource | null;
  selectedFiles: string[];
  onChange: (selectedFiles: string[]) => void;
}) {
  const files = source?.changedFiles ?? [];
  if (!source || source.sourceType === "manual" || files.length === 0) {
    return null;
  }

  const normalizedSelected = selectedFiles.filter((file) => files.includes(file));
  const reviewingAll = normalizedSelected.length === 0;
  const selectedSet = new Set(reviewingAll ? files : normalizedSelected);
  const visibleFiles = files.slice(0, 50);
  const selectedCount = reviewingAll ? files.length : normalizedSelected.length;

  function updateSelection(nextFiles: string[]) {
    const unique = nextFiles.filter((file, index, array) => files.includes(file) && array.indexOf(file) === index);
    onChange(unique.length === files.length ? [] : unique);
  }

  function toggleFile(file: string) {
    if (reviewingAll) {
      updateSelection(files.filter((candidate) => candidate !== file));
      return;
    }
    if (selectedSet.has(file)) {
      updateSelection(normalizedSelected.filter((candidate) => candidate !== file));
    } else {
      updateSelection([...normalizedSelected, file]);
    }
  }

  return (
    <div className="min-w-0 rounded-lg border border-border bg-background p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm font-medium">审查文件范围</div>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">
            默认审查全部文件；勾选后只会把选中文件的 diff 发送给模型，适合大改动先分批审查。
          </p>
        </div>
        <Badge variant={reviewingAll ? "success" : "warning"}>
          {reviewingAll ? `全部 ${files.length}` : `已选 ${selectedCount}/${files.length}`}
        </Badge>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        <Button type="button" size="sm" variant={reviewingAll ? "default" : "secondary"} onClick={() => onChange([])}>
          审查全部
        </Button>
        <Button type="button" size="sm" variant="secondary" onClick={() => updateSelection(files.slice(0, Math.min(10, files.length)))}>
          只审前 10 个
        </Button>
        <Button type="button" size="sm" variant="secondary" onClick={() => updateSelection(files.slice(0, Math.min(20, files.length)))}>
          只审前 20 个
        </Button>
      </div>
      <div className="mt-3 grid max-h-72 gap-2 overflow-auto pr-1">
        {visibleFiles.map((file) => (
          <label
            key={file}
            className="flex min-w-0 cursor-pointer items-center gap-3 rounded-md border border-border bg-card px-3 py-2 text-sm hover:border-primary/40"
          >
            <input
              type="checkbox"
              className="size-4 shrink-0 accent-sky-400"
              checked={selectedSet.has(file)}
              onChange={() => toggleFile(file)}
            />
            <span className="min-w-0 truncate font-mono text-xs">{file}</span>
          </label>
        ))}
      </div>
      {files.length > visibleFiles.length ? (
        <p className="mt-2 text-xs text-muted-foreground">
          还有 {files.length - visibleFiles.length} 个文件未展开。需要精确选择时，可以先用分支或工作区把改动拆小。
        </p>
      ) : null}
    </div>
  );
}

function reviewSourceIcon(sourceType: string): ReactNode {
  if (sourceType === "working_tree") return <GitPullRequest className="size-4 text-primary" />;
  if (sourceType === "current_branch") return <GitBranch className="size-4 text-primary" />;
  if (sourceType === "last_commit") return <Clock3 className="size-4 text-primary" />;
  if (sourceType === "auto") return <Sparkles className="size-4 text-primary" />;
  return <FileSearch className="size-4 text-primary" />;
}

function ReviewHistoryItem({ record, active, onOpen }: { record: ReviewRecord; active: boolean; onOpen: () => void }) {
  const summary = record.outcomeSummary;

  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        "rounded-lg border border-border bg-background p-3 text-left transition-colors hover:border-primary/40 hover:bg-secondary",
        active && "border-primary/60 bg-primary/10",
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <History className="size-4 text-primary" />
          <span className="font-mono text-sm font-semibold">Review #{record.id}</span>
        </div>
        <span className="text-xs text-muted-foreground">{formatTime(record.createdAt)}</span>
      </div>
      <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted-foreground">
        <Badge variant="secondary">{record.baseBranch ?? "base"} → {record.compareBranch ?? "compare"}</Badge>
        {record.runId ? <Badge variant="secondary">Run #{record.runId}</Badge> : null}
        {typeof record.score === "number" ? <Badge variant="secondary">score {round(record.score)}</Badge> : null}
      </div>
      {summary ? (
        <div className="mt-2 flex flex-wrap gap-1.5">
          <ReviewHistoryOutcomeBadge label="pending" value={summary.pending} variant={summary.pending ? "warning" : "secondary"} />
          <ReviewHistoryOutcomeBadge label="positive" value={summary.positiveOutcome} variant={summary.positiveOutcome ? "success" : "secondary"} />
          <ReviewHistoryOutcomeBadge label="negative" value={summary.negativeOutcome} variant={summary.negativeOutcome ? "warning" : "secondary"} />
        </div>
      ) : null}
      {record.summary ? <p className="mt-2 line-clamp-2 text-sm leading-5 text-muted-foreground">{record.summary}</p> : null}
    </button>
  );
}

function ReviewHistoryOutcomeBadge({
  label,
  value,
  variant,
}: {
  label: string;
  value: number;
  variant: BadgeVariant;
}) {
  return (
    <Badge variant={variant}>
      {label} {value}
    </Badge>
  );
}

function ReviewResultPanel({
  detail,
  focusedIssueId,
  onOpenRun,
  onUpdateIssue,
}: {
  detail: ReviewDetail | null;
  focusedIssueId?: number | null;
  onOpenRun: (runId: number) => void;
  onUpdateIssue: (issueId: number, status: string, note?: string | null) => void;
}) {
  const criticalCount = detail?.issues.filter((issue) => issue.severity === "critical").length ?? 0;
  const warningCount = detail?.issues.filter((issue) => issue.severity === "warning").length ?? 0;
  const outcomeSummary = detail ? reviewOutcomeSummary(detail) : null;

  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader className="min-w-0 max-md:flex-col max-md:items-start">
        <div className="min-w-0">
          <CardTitle>审查结果</CardTitle>
          <CardDescription>{detail ? detail.review.summary || "结构化 ReviewIssue" : "提交审查后显示问题列表。"}</CardDescription>
        </div>
        {detail?.review.runId ? (
          <Button size="sm" variant="secondary" className="shrink-0 whitespace-nowrap" onClick={() => onOpenRun(detail.review.runId as number)}>
            <Activity className="size-4" />
            追踪
          </Button>
        ) : null}
      </CardHeader>
      <CardContent>
        {!detail ? (
          <EmptyState text="还没有审查结果。" />
        ) : (
          <div className="grid gap-4">
            <div className="grid grid-cols-4 gap-3 max-lg:grid-cols-2">
              <Metric label="问题数" value={String(detail.issues.length)} tone={detail.issues.length ? "warn" : "good"} />
              <Metric label="Critical" value={String(criticalCount)} tone={criticalCount ? "bad" : "good"} />
              <Metric label="Warning" value={String(warningCount)} tone={warningCount ? "warn" : "good"} />
              <Metric label="评分" value={String(detail.review.score ?? "-")} tone="neutral" />
            </div>
            <div className="min-w-0 rounded-md border border-border bg-muted/30 p-3 text-sm">
              <div className="grid grid-cols-2 gap-3 max-md:grid-cols-1">
                <StateLine label="Review ID" value={String(detail.review.id)} />
                <StateLine label="报告文件" value={detail.review.reportPath ?? "-"} />
                <StateLine label="分支" value={`${detail.review.baseBranch ?? "-"} ... ${detail.review.compareBranch ?? "-"}`} />
                <StateLine label="Run ID" value={String(detail.review.runId ?? "-")} />
              </div>
            </div>
            {outcomeSummary ? <ReviewOutcomeSummaryPanel summary={outcomeSummary} /> : null}
            <ReviewMemorySignalsPanel signals={detail.reviewMemorySignals} />
            {detail.issues.length === 0 ? (
              <EmptyState text="这次审查没有解析出问题。" />
            ) : (
              <div className="grid gap-3">
                {detail.issues.map((issue) => (
                  <ReviewIssueCard
                    key={issue.id}
                    issue={issue}
                    focused={focusedIssueId === issue.id}
                    onUpdateStatus={onUpdateIssue}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ReviewOutcomeSummaryPanel({ summary }: { summary: ReviewOutcomeSummary }) {
  const metrics: Array<{ label: keyof ReviewOutcomeSummary; value: number; tone: "good" | "warn" | "bad" | "neutral" }> = [
    { label: "total", value: summary.total, tone: "neutral" },
    { label: "pending", value: summary.pending, tone: summary.pending ? "warn" : "neutral" },
    { label: "accepted", value: summary.accepted, tone: summary.accepted ? "good" : "neutral" },
    { label: "fixed", value: summary.fixed, tone: summary.fixed ? "good" : "neutral" },
    { label: "falsePositive", value: summary.falsePositive, tone: summary.falsePositive ? "warn" : "neutral" },
    { label: "rejected", value: summary.rejected, tone: summary.rejected ? "warn" : "neutral" },
    { label: "ignored", value: summary.ignored, tone: summary.ignored ? "warn" : "neutral" },
    { label: "positiveOutcome", value: summary.positiveOutcome, tone: summary.positiveOutcome ? "good" : "neutral" },
    { label: "negativeOutcome", value: summary.negativeOutcome, tone: summary.negativeOutcome ? "warn" : "neutral" },
  ];

  return (
    <section className="min-w-0 rounded-md border border-border bg-background p-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold">质量结果摘要</div>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">基于当前 ReviewIssue 状态实时计算，反馈后会随问题状态更新。</p>
        </div>
        <Badge variant={summary.pending ? "warning" : "secondary"}>{summary.pending} pending</Badge>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3 2xl:grid-cols-5">
        {metrics.map((metric) => (
          <ReviewOutcomeMetric key={metric.label} label={metric.label} value={metric.value} tone={metric.tone} />
        ))}
      </div>
    </section>
  );
}

function ReviewOutcomeMetric({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: "good" | "warn" | "bad" | "neutral";
}) {
  return (
    <div className={cn("min-w-0 rounded-md border px-2.5 py-2", outcomeMetricToneClass(tone))}>
      <div className="break-words text-[11px] leading-4 text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
    </div>
  );
}

function ReviewMemoryInventoryPanel({
  project,
  signals,
  loading,
  fetching,
  error,
  onRefresh,
  onOpenReview,
}: {
  project: Project | null;
  signals?: ReviewMemorySignal[] | null;
  loading: boolean;
  fetching: boolean;
  error: boolean;
  onRefresh: () => void;
  onOpenReview: (reviewId: number, issueId: number) => void;
}) {
  const [filterType, setFilterType] = useState<ReviewMemoryInventoryFilter>("all");
  const [searchText, setSearchText] = useState("");
  const items = signals ?? [];
  const normalizedSearch = searchText.trim().toLowerCase();
  const allGroups = splitReviewMemorySignals(items);
  const filteredItems = useMemo(
    () => items.filter((signal) => reviewMemorySignalMatchesFilter(signal, filterType, normalizedSearch)),
    [items, filterType, normalizedSearch],
  );
  const { confirmed, suppressions, other } = splitReviewMemorySignals(filteredItems);
  const filtersActive = filterType !== "all" || normalizedSearch.length > 0;
  const clearFilters = () => {
    setFilterType("all");
    setSearchText("");
  };

  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader>
        <div>
          <CardTitle>反馈记忆清单</CardTitle>
          <CardDescription>{project ? "当前项目已沉淀的 Review feedback memory。" : "先选择项目后查看反馈记忆。"}</CardDescription>
        </div>
        <Button variant="secondary" onClick={onRefresh} disabled={!project || fetching}>
          {fetching ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
          刷新
        </Button>
      </CardHeader>
      <CardContent>
        {!project ? (
          <EmptyState text="选择项目后会显示反馈记忆清单。" />
        ) : loading ? (
          <EmptyState text="正在加载反馈记忆。" />
        ) : error ? (
          <div className="rounded-lg border border-amber-400/30 bg-amber-400/10 p-4 text-sm leading-6 text-amber-800">
            反馈记忆加载失败。请确认后端已包含 inventory API。
          </div>
        ) : items.length === 0 ? (
          <EmptyState text="还没有 confirmed 或 suppression 反馈记忆。" />
        ) : (
          <div className="grid gap-3">
            <div className="grid gap-2 rounded-md border border-border/80 bg-background/60 p-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex min-w-0 flex-wrap gap-1.5">
                  {reviewMemoryFilterOptions.map((option) => (
                    <Button
                      key={option.value}
                      type="button"
                      variant={filterType === option.value ? "default" : "secondary"}
                      size="sm"
                      className="h-8 px-2.5 text-xs"
                      onClick={() => setFilterType(option.value)}
                    >
                      {option.label}
                      <span className="ml-1 font-mono text-[11px] opacity-80">
                        {reviewMemoryFilterCount(option.value, items, allGroups)}
                      </span>
                    </Button>
                  ))}
                </div>
                <div className="text-xs text-muted-foreground">
                  {filteredItems.length} / {items.length} signals
                </div>
              </div>
              <div className="flex min-w-0 flex-col gap-2 sm:flex-row">
                <div className="relative min-w-0 flex-1">
                  <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    value={searchText}
                    onChange={(event) => setSearchText(event.target.value)}
                    placeholder="搜索 title、path、note、description..."
                    className="h-9 pl-8 text-sm"
                  />
                </div>
                {filtersActive ? (
                  <Button type="button" variant="ghost" size="sm" className="h-9 whitespace-nowrap" onClick={clearFilters}>
                    清除过滤
                  </Button>
                ) : null}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <ReviewMemoryInventoryMetric label="confirmed" value={confirmed.length} tone="confirmed" />
              <ReviewMemoryInventoryMetric label="suppression" value={suppressions.length} tone="suppressed" />
            </div>
            {filteredItems.length === 0 ? (
              <div className="rounded-md border border-dashed border-border/80 bg-background/60 px-3 py-5 text-center">
                <div className="text-sm font-medium">没有匹配的反馈记忆</div>
                <p className="mt-1 text-xs leading-5 text-muted-foreground">调整类型过滤或搜索文本后再试。</p>
                <Button type="button" variant="secondary" size="sm" className="mt-3" onClick={clearFilters}>
                  清除过滤
                </Button>
              </div>
            ) : (
              <>
                <ReviewMemorySignalGroup
                  title="已确认问题模式"
                  description="accepted / fixed 反馈。"
                  signals={confirmed}
                  tone="confirmed"
                  showEmpty={filterType === "all"}
                  emptyText="暂无 confirmed 反馈。"
                  onOpenReview={onOpenReview}
                />
                <ReviewMemorySignalGroup
                  title="误报抑制模式"
                  description="false_positive / rejected 反馈。"
                  signals={suppressions}
                  tone="suppressed"
                  showEmpty={filterType === "all"}
                  emptyText="暂无 suppression 反馈。"
                  onOpenReview={onOpenReview}
                />
                {other.length ? (
                  <ReviewMemorySignalGroup
                    title="其他反馈信号"
                    description="暂未归入固定类别的历史反馈。"
                    signals={other}
                    tone="neutral"
                    onOpenReview={onOpenReview}
                  />
                ) : null}
              </>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

type ReviewMemoryInventoryFilter = "all" | "confirmed" | "suppression";

const reviewMemoryFilterOptions: Array<{ value: ReviewMemoryInventoryFilter; label: string }> = [
  { value: "all", label: "all" },
  { value: "confirmed", label: "confirmed" },
  { value: "suppression", label: "suppression" },
];

function reviewMemoryFilterCount(
  filter: ReviewMemoryInventoryFilter,
  items: ReviewMemorySignal[],
  groups: ReturnType<typeof splitReviewMemorySignals>,
) {
  if (filter === "confirmed") return groups.confirmed.length;
  if (filter === "suppression") return groups.suppressions.length;
  return items.length;
}

function reviewMemorySignalMatchesFilter(
  signal: ReviewMemorySignal,
  filter: ReviewMemoryInventoryFilter,
  normalizedSearch: string,
) {
  if (filter === "confirmed" && signal.signalType !== "confirmed_issue_pattern") return false;
  if (filter === "suppression" && signal.signalType !== "false_positive_pattern") return false;
  if (!normalizedSearch) return true;

  return [
    signal.title,
    signal.filePath,
    signal.note,
    signal.description,
    signal.impact,
    signal.suggestion,
    signal.feedbackStatus,
    signal.signalType,
  ].some((value) => value?.toLowerCase().includes(normalizedSearch));
}

function ReviewMemoryInventoryMetric({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: "confirmed" | "suppressed";
}) {
  return (
    <div className={cn("rounded-md border px-3 py-2", reviewSignalToneClass(tone))}>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
    </div>
  );
}

function ReviewMemorySignalsPanel({ signals }: { signals?: ReviewMemorySignal[] | null }) {
  const items = signals ?? [];
  if (items.length === 0) return null;

  const { confirmed, suppressions, other } = splitReviewMemorySignals(items);

  return (
    <section className="min-w-0 rounded-md border border-border bg-background p-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold">反馈记忆信号</div>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">本次 Review 使用的历史人工反馈，用来强化已确认模式或抑制重复误报。</p>
        </div>
        <Badge variant="secondary">{items.length} 条</Badge>
      </div>
      <div className="mt-3 grid gap-3 lg:grid-cols-2">
        <ReviewMemorySignalGroup
          title="已确认问题模式"
          description="accepted / fixed 反馈会让相似问题更值得关注。"
          signals={confirmed}
          tone="confirmed"
        />
        <ReviewMemorySignalGroup
          title="误报抑制模式"
          description="false_positive / rejected 反馈会提醒后续审查降低重复误报。"
          signals={suppressions}
          tone="suppressed"
        />
        <ReviewMemorySignalGroup
          title="其他反馈信号"
          description="暂未归入固定类别的历史反馈。"
          signals={other}
          tone="neutral"
        />
      </div>
    </section>
  );
}

function ReviewMemorySignalGroup({
  title,
  description,
  signals,
  tone,
  showEmpty = false,
  emptyText = "暂无反馈信号。",
  onOpenReview,
}: {
  title: string;
  description: string;
  signals: ReviewMemorySignal[];
  tone: "confirmed" | "suppressed" | "neutral";
  showEmpty?: boolean;
  emptyText?: string;
  onOpenReview?: (reviewId: number, issueId: number) => void;
}) {
  if (signals.length === 0 && !showEmpty) return null;

  return (
    <div className={cn("min-w-0 rounded-md border p-3", reviewSignalToneClass(tone))}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-medium">{title}</div>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">{description}</p>
        </div>
        <Badge variant={tone === "suppressed" ? "warning" : tone === "confirmed" ? "success" : "secondary"}>{signals.length}</Badge>
      </div>
      {signals.length ? (
        <div className="mt-3 grid gap-2">
          {signals.map((signal, index) => (
            <ReviewMemorySignalItem
              key={`${signal.signalType}:${signal.reviewId}:${signal.issueId}:${signal.updatedAt ?? index}`}
              signal={signal}
              onOpenReview={onOpenReview}
            />
          ))}
        </div>
      ) : (
        <div className="mt-3 rounded-md border border-dashed border-border/80 bg-background/60 px-3 py-4 text-sm text-muted-foreground">
          {emptyText}
        </div>
      )}
    </div>
  );
}

function splitReviewMemorySignals(signals: ReviewMemorySignal[]) {
  return {
    confirmed: signals.filter((signal) => signal.signalType === "confirmed_issue_pattern"),
    suppressions: signals.filter((signal) => signal.signalType === "false_positive_pattern"),
    other: signals.filter((signal) => signal.signalType !== "confirmed_issue_pattern" && signal.signalType !== "false_positive_pattern"),
  };
}

function ReviewMemorySignalItem({
  signal,
  onOpenReview,
}: {
  signal: ReviewMemorySignal;
  onOpenReview?: (reviewId: number, issueId: number) => void;
}) {
  const source = `Review #${signal.reviewId} · Issue #${signal.issueId}`;
  const location = signal.filePath ? `${signal.filePath}${signal.lineNumber ? `:${signal.lineNumber}` : ""}` : null;
  const humanNote = signalText(signal.note);
  const contextRows = [
    { label: "问题", value: signalText(signal.description) },
    { label: "影响", value: signalText(signal.impact) },
    { label: "建议", value: signalText(signal.suggestion) },
  ].filter((item): item is { label: string; value: string } => Boolean(item.value));

  return (
    <div className="min-w-0 rounded-md border border-border/70 bg-muted/20 p-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Badge variant={signal.signalType === "false_positive_pattern" ? "warning" : signal.signalType === "confirmed_issue_pattern" ? "success" : "secondary"}>
          {reviewSignalTypeLabel(signal.signalType)}
        </Badge>
        <Badge variant={statusBadge(signal.feedbackStatus)}>{signal.feedbackStatus}</Badge>
        <span className="font-mono text-xs text-muted-foreground">{source}</span>
      </div>
      <div className="mt-2 break-words text-sm font-medium">{signal.title}</div>
      {location ? <div className="mt-2 truncate rounded bg-background px-2 py-1 font-mono text-xs text-muted-foreground">{location}</div> : null}
      {humanNote ? (
        <div className="mt-3 rounded-md border border-primary/15 bg-primary/5 px-3 py-2">
          <div className="text-[11px] font-medium uppercase tracking-wide text-primary/80">人类反馈</div>
          <p className="mt-1 line-clamp-3 whitespace-pre-wrap break-words text-xs leading-5">{humanNote}</p>
        </div>
      ) : null}
      {contextRows.length ? (
        <div className="mt-3 grid gap-2">
          {contextRows.map((item) => (
            <div key={item.label} className="min-w-0">
              <div className="text-[11px] font-medium text-muted-foreground">{item.label}</div>
              <p className="mt-1 line-clamp-2 break-words text-xs leading-5 text-muted-foreground">{item.value}</p>
            </div>
          ))}
        </div>
      ) : null}
      {onOpenReview ? (
        <div className="mt-3 flex justify-end">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="w-full whitespace-nowrap sm:w-auto"
            onClick={() => onOpenReview(signal.reviewId, signal.issueId)}
          >
            <History className="size-3.5" />
            打开来源 Review
          </Button>
        </div>
      ) : null}
    </div>
  );
}

function signalText(value?: string | null) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function ReviewIssueCard({
  issue,
  focused = false,
  onUpdateStatus,
}: {
  issue: ReviewIssue;
  focused?: boolean;
  onUpdateStatus: (issueId: number, status: string, note?: string | null) => void;
}) {
  const articleRef = useRef<HTMLElement | null>(null);
  const [note, setNote] = useState(issue.note ?? "");

  useEffect(() => {
    setNote(issue.note ?? "");
  }, [issue.id, issue.note]);

  useEffect(() => {
    if (!focused) return;
    articleRef.current?.focus({ preventScroll: true });
    articleRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [focused, issue.id]);

  function saveStatus(status: string) {
    onUpdateStatus(issue.id, status, note.trim() || null);
  }

  return (
    <article
      ref={articleRef}
      tabIndex={focused ? -1 : undefined}
      className={cn(
        "min-w-0 overflow-hidden rounded-lg border bg-background p-4 transition-colors",
        focused ? "border-primary/70 bg-primary/5 ring-1 ring-primary/25" : "border-border",
      )}
    >
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Badge variant={severityBadge(issue.severity)}>{issue.severity}</Badge>
        <Badge variant={statusBadge(issue.status)}>{issue.status ?? "pending"}</Badge>
        {issue.confidence ? <Badge variant="secondary">{issue.confidence}</Badge> : null}
        {focused ? <Badge variant="secondary">来源反馈记忆</Badge> : null}
        <h3 className="min-w-0 flex-1 break-words font-semibold">{issue.title}</h3>
      </div>
      <div className="mt-3 truncate rounded-md bg-muted px-2 py-1 font-mono text-xs text-muted-foreground">
        {issue.filePath ?? "未定位文件"}{issue.lineNumber ? `:${issue.lineNumber}` : ""}
      </div>
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <TextBlock title="问题" text={issue.description} />
        <TextBlock title="建议" text={issue.suggestion || "暂无建议。"} />
      </div>
      {issue.impact ? <TextBlock className="mt-4" title="影响" text={issue.impact} /> : null}
      <Label className="mt-4 block">
        反馈备注
        <Textarea
          className="mt-2 min-h-20 resize-y"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          placeholder="补充采纳、误报、已修复或拒绝的原因"
        />
      </Label>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" onClick={() => saveStatus("accepted")}>采纳</Button>
        <Button size="sm" variant="secondary" onClick={() => saveStatus("false_positive")}>误报</Button>
        <Button size="sm" variant="secondary" onClick={() => saveStatus("fixed")}>已修复</Button>
        <Button size="sm" variant="ghost" onClick={() => saveStatus("rejected")}>拒绝</Button>
      </div>
    </article>
  );
}

function DecisionWorkspace({
  project,
  decisions,
  onNotice,
  onOpenRun,
}: {
  project: Project | null;
  decisions: DecisionCard[];
  onNotice: (message: string) => void;
  onOpenRun: (runId: number) => void;
}) {
  const [query, setQuery] = useState("这个新场景是否可以复用已有工程决策？");
  const [tags, setTags] = useState("");
  const [searchResult, setSearchResult] = useState<DecisionSearchResponse | null>(null);
  const [adviceResult, setAdviceResult] = useState<DecisionReuseAdviceResult | null>(null);

  const search = useMutation({
    mutationFn: () => api.searchDecisions({ query, projectId: project?.id ?? null, tags: splitTags(tags), topK: 5 }),
    onSuccess: (data) => {
      setSearchResult(data);
      setAdviceResult(null);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "决策检索失败。"),
  });

  const advice = useMutation({
    mutationFn: () => api.reuseAdvice({ query, projectId: project?.id ?? null, tags: splitTags(tags), topK: 5 }),
    onSuccess: (data) => {
      setAdviceResult(data);
      setSearchResult(null);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "复用建议生成失败。"),
  });

  const feedback = useMutation({
    mutationFn: ({ recordId, status }: { recordId: number; status: string }) =>
      api.updateReuseFeedback(recordId, { status, userFeedback: statusLabel(status) }),
    onSuccess: () => onNotice("复用反馈已保存。"),
    onError: (error) => onNotice(error instanceof Error ? error.message : "反馈保存失败。"),
  });

  return (
    <div className="grid grid-cols-1 gap-5 2xl:grid-cols-[minmax(0,420px)_minmax(0,1fr)]">
      <section className="grid min-w-0 gap-5">
        <Card>
          <CardHeader>
            <div>
              <CardTitle>决策检索</CardTitle>
              <CardDescription>从历史 DecisionCard 中找相似工程判断。</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="grid gap-4">
            <Label>
              当前问题
              <Textarea className="min-h-32" value={query} onChange={(event) => setQuery(event.target.value)} />
            </Label>
            <Label>
              标签过滤
              <Input value={tags} onChange={(event) => setTags(event.target.value)} placeholder="pagination, performance" />
            </Label>
            <div className="flex flex-wrap gap-2">
              <Button onClick={() => search.mutate()} disabled={search.isPending}>
                {search.isPending ? <Loader2 className="size-4 animate-spin" /> : <Search className="size-4" />}
                检索
              </Button>
              <Button variant="secondary" onClick={() => advice.mutate()} disabled={advice.isPending}>
                {advice.isPending ? <Loader2 className="size-4 animate-spin" /> : <Sparkles className="size-4" />}
                生成复用建议
              </Button>
            </div>
          </CardContent>
        </Card>
        <CreateDecisionDialog project={project} onNotice={onNotice} />
      </section>

      <section className="grid min-w-0 gap-5">
        <Card>
          <CardHeader>
            <div>
              <CardTitle>决策结果</CardTitle>
              <CardDescription>{adviceResult ? `Reuse Record #${adviceResult.reuseRecordId}` : "检索结果和复用建议会显示在这里。"}</CardDescription>
            </div>
            {adviceResult?.runId ? (
              <Button size="sm" variant="secondary" className="shrink-0 whitespace-nowrap" onClick={() => onOpenRun(adviceResult.runId)}>
                <Activity className="size-4" />
                追踪
              </Button>
            ) : null}
          </CardHeader>
          <CardContent>
            {adviceResult ? (
              <div className="grid gap-4">
                <pre className="max-h-96 overflow-auto rounded-md border border-border bg-background p-4 text-sm leading-6 whitespace-pre-wrap">
                  {adviceResult.advice}
                </pre>
                <div className="flex flex-wrap gap-2">
                  {["accepted", "partially_reused", "rejected", "false_positive"].map((status) => (
                    <Button
                      key={status}
                      size="sm"
                      variant={status === "accepted" ? "default" : "secondary"}
                      onClick={() => feedback.mutate({ recordId: adviceResult.reuseRecordId, status })}
                    >
                      {statusLabel(status)}
                    </Button>
                  ))}
                </div>
                <DecisionMatches matches={adviceResult.matchedDecisions} />
              </div>
            ) : searchResult ? (
              <DecisionMatches matches={searchResult.matches} />
            ) : (
              <EmptyState text="还没有决策检索结果。" />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>活跃决策卡</CardTitle>
            <CardDescription>{decisions.length} 张与当前项目相关的 active 决策。</CardDescription>
          </CardHeader>
          <CardContent>
            {decisions.length === 0 ? (
              <EmptyState text="还没有活跃决策卡。" />
            ) : (
              <div className="grid gap-3">
                {decisions.map((decision) => (
                  <DecisionCardView key={decision.id} decision={decision} />
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </section>
    </div>
  );
}

function DecisionMatches({ matches }: { matches: DecisionSearchResponse["matches"] }) {
  if (matches.length === 0) {
    return <EmptyState text="没有召回相似决策。" />;
  }
  return (
    <div className="grid gap-3">
      {matches.map((match) => (
        <div key={match.decision.id} className="rounded-lg border border-border bg-background p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="success">score {round(match.score)}</Badge>
            {(match.matchReasons ?? []).slice(0, 3).map((reason) => (
              <Badge key={reason}>{reason}</Badge>
            ))}
            <span className="font-semibold">{match.decision.title}</span>
          </div>
          <p className="mt-3 text-sm leading-6 text-muted-foreground">{match.decision.decision || match.decision.scenario}</p>
        </div>
      ))}
    </div>
  );
}

function DecisionCardView({ decision }: { decision: DecisionCard }) {
  return (
    <article className="rounded-lg border border-border bg-background p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="secondary">Decision #{decision.id}</Badge>
        <Badge variant={statusBadge(decision.status)}>{decision.status}</Badge>
        <Badge variant="secondary">{decision.embeddingStatus ?? "embedding"}</Badge>
        <h3 className="font-semibold">{decision.title}</h3>
      </div>
      <p className="mt-3 text-sm leading-6 text-muted-foreground">{decision.decision || decision.scenario}</p>
      <div className="mt-3 flex flex-wrap gap-2">
        {(decision.tags ?? []).map((tag) => (
          <Badge key={tag}>{tag}</Badge>
        ))}
      </div>
    </article>
  );
}

function CreateDecisionDialog({ project, onNotice }: { project: Project | null; onNotice: (message: string) => void }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    title: "",
    scenario: "",
    options: "",
    decision: "",
    reasons: "",
    tradeOffs: "",
    applicableWhen: "",
    notApplicableWhen: "",
    outcome: "",
    tags: "",
    status: "active",
  });

  const create = useMutation({
    mutationFn: () =>
      api.createDecision({
        projectId: project?.id ?? null,
        title: form.title,
        scenario: form.scenario,
        options: lines(form.options),
        decision: form.decision,
        reasons: lines(form.reasons),
        tradeOffs: lines(form.tradeOffs),
        applicableWhen: lines(form.applicableWhen),
        notApplicableWhen: lines(form.notApplicableWhen),
        outcome: form.outcome || null,
        evidence: [],
        status: form.status,
        tags: splitTags(form.tags),
      }),
    onSuccess: () => {
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: ["decisions"] });
      onNotice("决策卡已创建并索引。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "决策卡创建失败。"),
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="secondary">
          <Plus className="size-4" />
          新建决策卡
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[86vh] max-w-3xl overflow-auto">
        <DialogHeader>
          <DialogTitle>新建 DecisionCard</DialogTitle>
          <DialogDescription>结构化保存一次工程判断。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <Label>标题<Input value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} /></Label>
          <Label>场景<Textarea value={form.scenario} onChange={(event) => setForm({ ...form, scenario: event.target.value })} /></Label>
          <Label>选项<Textarea value={form.options} onChange={(event) => setForm({ ...form, options: event.target.value })} placeholder="每行一个选项" /></Label>
          <Label>决策<Textarea value={form.decision} onChange={(event) => setForm({ ...form, decision: event.target.value })} /></Label>
          <div className="grid grid-cols-2 gap-3 max-md:grid-cols-1">
            <Label>理由<Textarea value={form.reasons} onChange={(event) => setForm({ ...form, reasons: event.target.value })} /></Label>
            <Label>权衡<Textarea value={form.tradeOffs} onChange={(event) => setForm({ ...form, tradeOffs: event.target.value })} /></Label>
            <Label>适用条件<Textarea value={form.applicableWhen} onChange={(event) => setForm({ ...form, applicableWhen: event.target.value })} /></Label>
            <Label>不适用条件<Textarea value={form.notApplicableWhen} onChange={(event) => setForm({ ...form, notApplicableWhen: event.target.value })} /></Label>
          </div>
          <Label>结果<Input value={form.outcome} onChange={(event) => setForm({ ...form, outcome: event.target.value })} /></Label>
          <Label>标签<Input value={form.tags} onChange={(event) => setForm({ ...form, tags: event.target.value })} placeholder="pagination, performance" /></Label>
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="ghost">取消</Button></DialogClose>
          <Button onClick={() => create.mutate()} disabled={create.isPending}>保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function KnowledgeWorkspace({
  project,
  sources,
  settings,
  onNotice,
  onOpenRun,
}: {
  project: Project | null;
  sources: KnowledgeSource[];
  settings?: LlmSettings;
  onNotice: (message: string) => void;
  onOpenRun: (runId: number) => void;
}) {
  const projectSource = useMemo(() => findKnowledgeSourceForProject(sources, project), [sources, project?.rootPath]);
  const [query, setQuery] = useState("SourceEvidenceLoop 是怎么选择 primary evidence 的？");
  const [answerResult, setAnswerResult] = useState<RagAnswerResult | null>(null);
  const [askStage, setAskStage] = useState<AskStage>("idle");
  const askStageTimers = useRef<number[]>([]);
  const sourceReady = projectSource?.status === "indexed";
  const queryReady = query.trim().length > 0 && Boolean(project) && sourceReady;

  function clearAskStageTimers() {
    askStageTimers.current.forEach((timer) => window.clearTimeout(timer));
    askStageTimers.current = [];
  }

  useEffect(() => clearAskStageTimers, []);

  const ask = useMutation({
    mutationFn: () =>
      api.askKnowledge({
        query,
        sourceId: projectSource?.id ?? null,
        topK: 5,
      }),
    onMutate: () => {
      clearAskStageTimers();
      setAnswerResult(null);
      setAskStage("understanding");
      askStageTimers.current = [
        window.setTimeout(() => setAskStage("evidence"), 650),
        window.setTimeout(() => setAskStage("answering"), 1500),
      ];
    },
    onSuccess: (data) => {
      clearAskStageTimers();
      setAnswerResult(data);
      const evaluation = data.evidenceEvaluation;
      setAskStage(evaluation?.noAnswerRequired || evaluation?.sufficient === false ? "insufficient" : "completed");
    },
    onError: (error) => {
      clearAskStageTimers();
      setAskStage("error");
      onNotice(error instanceof Error ? error.message : "知识库问答失败。");
    },
  });

  const requestPending = ask.isPending;
  const requestError = mutationErrorMessage(ask.error);
  const examples = [
    "SourceEvidenceLoop 是怎么选择 primary evidence 的？",
    "SourceEvidenceLoop 的测试在哪里？",
    "LLM provider/client 是在哪里配置的？",
    "Knowledge RAG 如何记录 retrieval trace？",
    "这个项目是否支持一个完全不存在的能力？",
  ];

  return (
    <div className="mx-auto grid w-full max-w-5xl gap-5">
      <Card className="min-w-0 overflow-hidden">
        <CardHeader>
          <div>
            <CardTitle>知识问答</CardTitle>
            <CardDescription>{project ? `${project.name} · ${projectSource?.status ?? "未索引"}` : "先在首页选择项目并建立索引。"}</CardDescription>
          </div>
          {answerResult?.runId ? (
            <Button size="sm" variant="secondary" className="shrink-0 whitespace-nowrap" onClick={() => onOpenRun(answerResult.runId)}>
              <Activity className="size-4" />
              追踪
            </Button>
          ) : null}
        </CardHeader>
        <CardContent className="grid gap-4">
          {!project ? <InlineState tone="empty" text="未选择项目。请回到首页选择项目。" /> : null}
          {project && !sourceReady ? <InlineState tone="empty" text="当前项目还没有完成索引。请回到首页建立或刷新索引。" /> : null}
          <Label className="min-w-0">
            问题
            <Textarea className="min-h-32 bg-card text-base leading-7" value={query} onChange={(event) => setQuery(event.target.value)} />
          </Label>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap gap-2">
              {examples.map((item) => (
                <Button key={item} type="button" size="sm" variant="secondary" onClick={() => setQuery(item)}>
                  {item}
                </Button>
              ))}
            </div>
            <Button onClick={() => ask.mutate()} disabled={!queryReady || requestPending}>
              {ask.isPending ? <Loader2 className="size-4 animate-spin" /> : <Sparkles className="size-4" />}
              生成答案
            </Button>
          </div>
          <div className="grid gap-2 rounded-md border border-border bg-muted p-3 text-xs text-muted-foreground md:grid-cols-3">
            <StateLine label="project" value={project?.name ?? "未选择"} />
            <StateLine label="source" value={projectSource ? `${projectSource.name} (#${projectSource.id})` : "未建立"} />
            <StateLine label="model" value={settings ? `${settings.provider} / ${settings.model}` : "-"} />
          </div>
          {requestPending ? (
            <QuestionProgress stage={askStage} />
          ) : requestError ? (
            <InlineState tone="error" text={requestError} />
          ) : null}
        </CardContent>
      </Card>

      <KnowledgeResult
        answer={answerResult}
        loading={requestPending}
        loadingStage={askStage}
        error={requestError}
        providerModel={settings ? `${settings.provider} / ${settings.model}` : null}
      />
    </div>
  );
}

function MetricTile({ label, value, tone }: { label: string; value: ReactNode; tone?: "success" | "warning" | "danger" }) {
  const toneClass =
    tone === "success"
      ? "text-emerald-800"
      : tone === "warning"
        ? "text-amber-800"
        : tone === "danger"
          ? "text-red-800"
          : "text-foreground";
  return (
    <div className="rounded-lg border border-border bg-background p-4">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className={cn("mt-2 text-2xl font-semibold", toneClass)}>{value}</div>
    </div>
  );
}

function CoverageBadges({
  coverage,
  className,
}: {
  coverage?: EvidenceCoverageReport["coverage"] | null;
  className?: string;
}) {
  const entries = coverageEntries(coverage);
  if (!entries.length) {
    return <div className={cn("text-xs text-muted-foreground", className)}>还没有覆盖度明细。</div>;
  }
  return (
    <div className={cn("flex min-w-0 flex-wrap gap-2", className)}>
      {entries.map(([type, count]) => (
        <Badge key={type} variant="secondary">{evidenceTypeLabel(type)} {count}</Badge>
      ))}
    </div>
  );
}

function CoverageWarnings({ warnings }: { warnings: string[] }) {
  if (!warnings.length) return null;
  return (
    <div className="mt-3 grid gap-2">
      {warnings.map((warning) => (
        <div key={warning} className="flex min-w-0 items-start gap-2 rounded-md border border-amber-700/20 bg-amber-600/10 p-2 text-xs leading-5 text-amber-800">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          <span className="min-w-0 break-words">{warning}</span>
        </div>
      ))}
    </div>
  );
}

function EvidenceTypeRow({ title, types }: { title: string; types: KnowledgeEvidenceType[] }) {
  if (!types.length) return null;
  return (
    <div className="mt-3 flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <span>{title}</span>
      {types.map((type) => (
        <Badge key={type} variant="secondary">{evidenceTypeLabel(type)}</Badge>
      ))}
    </div>
  );
}

function KnowledgeIndexStatus({
  source,
  indexing,
  indexResult,
  indexedAt,
}: {
  source: KnowledgeSource;
  indexing: boolean;
  indexResult: KnowledgeIndexResult | null;
  indexedAt: string | null;
}) {
  if (indexing) {
    return (
      <div className="mt-3 flex min-w-0 items-start gap-3 rounded-lg border border-primary/25 bg-primary/10 p-3 text-sm">
        <Loader2 className="mt-0.5 size-4 shrink-0 animate-spin text-primary" />
        <div className="min-w-0">
          <div className="font-medium text-primary">正在索引 Source #{source.id}</div>
          <p className="mt-1 leading-5 text-muted-foreground">正在扫描文件、切分内容、生成向量和覆盖度报告。完成后这里会显示结果。</p>
        </div>
      </div>
    );
  }
  if (!indexResult) {
    return null;
  }
  return (
    <div className="mt-3 rounded-lg border border-emerald-700/20 bg-emerald-700/8 p-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <CheckCircle2 className="size-4 text-emerald-800" />
        <span className="font-medium text-emerald-800">索引完成</span>
        <Badge variant="success">{indexResult.documentsIndexed} 文档</Badge>
        <Badge variant="success">{indexResult.chunksIndexed} 片段</Badge>
        {indexedAt ? <span className="text-xs text-muted-foreground">{formatTime(indexedAt)}</span> : null}
      </div>
      <CoverageBadges coverage={indexResult.coverageReport?.coverage} className="mt-2" />
      <CoverageWarnings warnings={indexResult.coverageReport?.warnings ?? []} />
    </div>
  );
}

function KnowledgeResult({
  answer,
  loading,
  loadingStage,
  error,
  providerModel,
}: {
  answer: RagAnswerResult | null;
  loading: boolean;
  loadingStage: AskStage;
  error: string | null;
  providerModel: string | null;
}) {
  const queryPlan = answer?.queryPlan ?? null;
  const citations = answer?.citations ?? [];
  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader>
        <CardTitle>Answer</CardTitle>
        <CardDescription>{answer ? `Run #${answer.runId} · Retrieval #${answer.retrievalRecordId}` : "答案和引用会显示在这里。"}</CardDescription>
      </CardHeader>
      <CardContent>
        {loading ? (
          <QuestionProgress stage={loadingStage} compact />
        ) : error ? (
          <InlineState tone="error" text={error} />
        ) : answer ? (
          <div className="grid gap-5">
            <AnswerBlock answer={answer.answer} evaluation={answer.evidenceEvaluation ?? null} citationCount={citations.length} />
            <CitationList citations={citations} />
            <details className="rounded-lg border border-border bg-muted p-4">
              <summary className="cursor-pointer text-sm font-medium text-foreground">Trace / Evidence details</summary>
              <div className="mt-4 grid gap-4">
                <ResultTracePanel
                  runId={answer.runId}
                  retrievalRecordId={answer.retrievalRecordId}
                  query={answer.query}
                  rewrittenQuery={answer.rewrittenQuery}
                  citationCount={citations.length}
                  providerModel={providerModel}
                />
                <QueryPlanPanel plan={queryPlan} />
                <EvidenceEvaluationPanel evaluation={answer.evidenceEvaluation ?? null} />
              </div>
            </details>
          </div>
        ) : (
          <EmptyState text="还没有知识库结果。" />
        )}
      </CardContent>
    </Card>
  );
}

function QuestionProgress({ stage, compact = false }: { stage: AskStage; compact?: boolean }) {
  const steps: Array<{ id: AskStage; label: string }> = [
    { id: "understanding", label: "正在理解问题" },
    { id: "evidence", label: "正在选择证据" },
    { id: "answering", label: "正在生成回答" },
  ];
  const activeIndex = Math.max(0, steps.findIndex((step) => step.id === stage));

  return (
    <div className={cn("rounded-lg border border-primary/25 bg-primary/10", compact ? "p-3" : "p-4")}>
      <div className="flex min-w-0 flex-wrap items-center gap-3">
        {steps.map((step, index) => {
          const active = index === activeIndex;
          const done = index < activeIndex;
          return (
            <div key={step.id} className="flex min-w-0 items-center gap-2 text-sm">
              <span
                className={cn(
                  "grid size-5 shrink-0 place-items-center rounded-full border text-[11px]",
                  done && "border-emerald-300 bg-emerald-50 text-emerald-800",
                  active && "border-primary bg-card text-primary",
                  !done && !active && "border-border bg-muted text-muted-foreground",
                )}
              >
                {done ? "✓" : active ? <Loader2 className="size-3 animate-spin" /> : index + 1}
              </span>
              <span className={cn("whitespace-nowrap", active ? "text-foreground" : "text-muted-foreground")}>{step.label}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ResultTracePanel({
  runId,
  retrievalRecordId,
  query,
  rewrittenQuery,
  citationCount,
  providerModel,
}: {
  runId?: number | null;
  retrievalRecordId: number;
  query: string;
  rewrittenQuery: string;
  citationCount: number;
  providerModel?: string | null;
}) {
  return (
    <div className="grid gap-3 rounded-lg border border-border bg-card p-4 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
      <StateLine label="retrieval" value={`#${retrievalRecordId}`} />
      <StateLine label="run" value={runId ? `#${runId}` : "search only"} />
      <Badge variant={citationCount ? "success" : "warning"}>{citationCount} citations</Badge>
      <div className="min-w-0 md:col-span-3">
        <div className="grid gap-2 text-xs text-muted-foreground md:grid-cols-2">
          <div className="min-w-0 break-words">provider/model：{providerModel ?? "-"}</div>
          <div className="min-w-0 break-words">输入问题：{query}</div>
          <div className="min-w-0 break-words">检索问题：{rewrittenQuery || query}</div>
        </div>
      </div>
    </div>
  );
}

function EvidenceEvaluationPanel({ evaluation }: { evaluation: EvidenceEvaluation | null }) {
  if (!evaluation) return null;
  const missingRequired = evaluation.missingRequiredEvidenceTypes ?? [];
  const weakReasons = evaluation.weakEvidenceReasons ?? [];
  const reasons = evaluation.reasons ?? [];
  const assessments = evaluation.citationAssessments ?? [];
  return (
    <div className={cn("rounded-lg border p-4", evidenceEvaluationToneClass(evaluation))}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Badge variant={evidenceEvaluationBadge(evaluation)}>{evidenceEvaluationLabel(evaluation)}</Badge>
        <Badge variant="secondary">{evaluation.status || "unknown"}</Badge>
        <Badge variant="secondary">{evaluation.answerGuardDecision || "guard pending"}</Badge>
        <Badge variant={evaluation.sufficient ? "success" : "warning"}>
          {evaluation.sufficient ? "evidence sufficient" : "insufficient evidence"}
        </Badge>
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <EvidenceTypeSummary title="required" types={evaluation.requiredEvidenceTypes ?? []} matched={evaluation.matchedRequiredEvidenceTypes ?? []} />
        <EvidenceTypeSummary title="observed" types={evaluation.observedEvidenceTypes ?? []} />
      </div>
      {missingRequired.length ? (
        <EvidenceTypeRow title="缺失" types={missingRequired} />
      ) : null}
      {reasons.length || weakReasons.length ? (
        <div className="mt-3 grid gap-2 text-xs leading-5 text-muted-foreground">
          {[...reasons, ...weakReasons].slice(0, 5).map((reason) => (
            <div key={reason} className="min-w-0 break-words">{reason}</div>
          ))}
        </div>
      ) : null}
      {assessments.length ? (
        <div className="mt-3 grid gap-2">
          {assessments.slice(0, 4).map((assessment) => (
            <div key={`${assessment.citationIndex}-${assessment.sourcePath}`} className="rounded-md border border-border bg-background/80 p-3 text-xs">
              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <Badge variant={assessment.weakEvidence ? "warning" : "success"}>citation #{assessment.citationIndex + 1}</Badge>
                <span className="min-w-0 break-all font-mono text-muted-foreground">{assessment.sourcePath}</span>
              </div>
              <div className="mt-2 flex min-w-0 flex-wrap gap-2">
                {(assessment.sourceReliabilities ?? []).map((item) => (
                  <Badge key={item} variant={item === "primary" ? "success" : "secondary"}>{sourceReliabilityLabel(item)}</Badge>
                ))}
                {(assessment.sourceKinds ?? []).slice(0, 4).map((item) => (
                  <Badge key={item} variant="secondary">{sourceKindLabel(item)}</Badge>
                ))}
              </div>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function EvidenceTypeSummary({ title, types, matched = [] }: { title: string; types: KnowledgeEvidenceType[]; matched?: KnowledgeEvidenceType[] }) {
  return (
    <div className="min-w-0 rounded-md border border-border bg-background/80 p-3">
      <div className="text-xs font-medium text-muted-foreground">{title}</div>
      <div className="mt-2 flex min-w-0 flex-wrap gap-2">
        {types.length ? types.map((type) => (
          <Badge key={type} variant={matched.includes(type) ? "success" : "secondary"}>{evidenceTypeLabel(type)}</Badge>
        )) : <span className="text-xs text-muted-foreground">none</span>}
      </div>
    </div>
  );
}

function AnswerBlock({ answer, evaluation, citationCount }: { answer: string; evaluation: EvidenceEvaluation | null; citationCount: number }) {
  const noAnswer = Boolean(evaluation?.noAnswerRequired || (evaluation && !evaluation.sufficient));
  return (
    <div className={cn("rounded-md border p-5", noAnswer ? "border-amber-700/20 bg-amber-600/10" : "border-border bg-card")}>
      <div className="mb-3 flex min-w-0 flex-wrap items-center gap-2">
        <Badge variant={noAnswer ? "warning" : "success"}>{noAnswer ? "no-answer guard" : "answer"}</Badge>
        <Badge variant={citationCount ? "success" : "warning"}>{citationCount} source paths</Badge>
      </div>
      <div className="markdown-answer">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {answer || "证据不足，未生成可采信答案。"}
        </ReactMarkdown>
      </div>
    </div>
  );
}

function InlineState({ tone, text }: { tone: "loading" | "error" | "empty"; text: string }) {
  const toneClass =
    tone === "loading"
      ? "border-primary/25 bg-primary/10 text-primary"
      : tone === "error"
        ? "border-red-700/20 bg-red-700/10 text-red-800"
        : "border-border bg-background text-muted-foreground";
  return (
    <div className={cn("flex min-w-0 items-start gap-3 rounded-lg border p-4 text-sm", toneClass)}>
      {tone === "loading" ? <Loader2 className="mt-0.5 size-4 shrink-0 animate-spin" /> : tone === "error" ? <AlertTriangle className="mt-0.5 size-4 shrink-0" /> : <FileSearch className="mt-0.5 size-4 shrink-0" />}
      <span className="min-w-0 break-words">{text}</span>
    </div>
  );
}

function KnowledgeIndexResultPanel({ result, indexedAt }: { result: KnowledgeIndexResult; indexedAt: string | null }) {
  const warnings = result.coverageReport?.warnings ?? [];
  return (
    <div className="grid gap-4">
      <div className="grid gap-3 md:grid-cols-3">
        <MetricTile label="文档" value={result.documentsIndexed} tone="success" />
        <MetricTile label="片段" value={result.chunksIndexed} tone="success" />
        <MetricTile label="完成时间" value={formatTime(indexedAt ?? undefined)} />
      </div>
      <div className="rounded-lg border border-border bg-background p-4">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <CheckCircle2 className="size-4 text-emerald-800" />
          <div className="font-medium">Source #{result.sourceId} 已完成索引</div>
          <Badge variant={warnings.length ? "warning" : "success"}>{warnings.length ? `${warnings.length} 条提醒` : "覆盖正常"}</Badge>
        </div>
        <CoverageBadges coverage={result.coverageReport?.coverage} className="mt-3" />
        <CoverageWarnings warnings={warnings} />
      </div>
    </div>
  );
}

function QueryPlanPanel({ plan }: { plan: KnowledgeQueryPlan | null }) {
  if (!plan) return null;
  const required = plan.requiredEvidenceTypes ?? [];
  const preferred = plan.preferredEvidenceTypes ?? [];
  const terms = plan.normalizedTerms ?? [];
  return (
    <div className="rounded-lg border border-border bg-background p-4">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Badge variant="secondary">{plan.answerMode || "answer"}</Badge>
        {required.length ? <Badge variant="warning">必须证据 {required.length}</Badge> : null}
        {preferred.length ? <Badge variant="success">偏好证据 {preferred.length}</Badge> : null}
      </div>
      {terms.length ? (
        <div className="mt-3 flex min-w-0 flex-wrap gap-2">
          {terms.slice(0, 12).map((term) => (
            <Badge key={term} variant="secondary">{term}</Badge>
          ))}
        </div>
      ) : null}
      <div className="mt-3 grid gap-2 text-xs text-muted-foreground md:grid-cols-2">
        <div className="min-w-0 break-words">检索问题：{plan.rewrittenQuery || plan.originalQuery}</div>
        <div className="min-w-0 break-words">缺证策略：{plan.noAnswerPolicy || "strict"}</div>
      </div>
      <EvidenceTypeRow title="需要" types={required} />
      <EvidenceTypeRow title="优先" types={preferred} />
    </div>
  );
}

function CitationList({ citations }: { citations: RagAnswerResult["citations"] }) {
  if (citations.length === 0) {
    return <InlineState tone="empty" text="没有召回引用片段，可作为 no-answer 或检索范围不足的验收状态。" />;
  }
  return (
    <section className="grid gap-3">
      <div>
        <h3 className="text-sm font-semibold">Sources</h3>
        <p className="mt-1 text-sm text-muted-foreground">引用路径单独展示，便于核验答案来源。</p>
      </div>
      {citations.map((citation, index) => (
        <article key={citation.chunkId} className="min-w-0 rounded-lg border border-border bg-card p-4">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <Badge variant="default">S{index + 1}</Badge>
            <Badge variant="success">score {round(citation.fusedScore)}</Badge>
            <Badge variant="secondary">keyword {round(citation.keywordScore)}</Badge>
            <Badge variant="secondary">vector {round(citation.vectorScore)}</Badge>
            {(citation.evidenceTypes ?? []).slice(0, 4).map((type) => (
              <Badge key={type} variant="secondary">{evidenceTypeLabel(type)}</Badge>
            ))}
            <span className="min-w-0 break-words font-medium">{citation.title || citation.sourceName}</span>
          </div>
          <div className="mt-3 grid gap-2 rounded-md border border-border bg-muted p-3 text-xs text-muted-foreground md:grid-cols-2">
            <StateLine label="source" value={`${citation.sourceName} (#${citation.sourceId})`} />
            <StateLine label="chunk" value={`document #${citation.documentId} · chunk #${citation.chunkId}`} />
            <div className="min-w-0 break-all font-mono md:col-span-2">{citation.filePath}</div>
            {citation.headingPath ? <div className="min-w-0 break-words md:col-span-2">{citation.headingPath}</div> : null}
          </div>
          <p className="mt-3 line-clamp-4 break-words text-sm leading-6 text-muted-foreground">{citation.content}</p>
        </article>
      ))}
    </section>
  );
}

function SettingsWorkspace({
  settings,
  loading,
  onNotice,
}: {
  settings?: LlmSettings;
  loading: boolean;
  onNotice: (message: string) => void;
}) {
  const queryClient = useQueryClient();
  const providers = settings?.supportedProviders?.length
    ? settings.supportedProviders
    : defaultLlmProviders();
  const pendingOrActive = settings?.pending ?? settings;
  const [provider, setProvider] = useState(pendingOrActive?.provider ?? "mock");
  const [model, setModel] = useState(pendingOrActive?.model ?? "mock-llm");
  const [timeoutSeconds, setTimeoutSeconds] = useState(timeoutToSecondsInput(pendingOrActive?.timeout));
  const [geminiApiKey, setGeminiApiKey] = useState("");
  const [deepseekApiKey, setDeepseekApiKey] = useState("");
  const [connectionCheckResult, setConnectionCheckResult] = useState<LlmConnectionCheckResult | null>(null);

  useEffect(() => {
    const next = settings?.pending ?? settings;
    if (!next) return;
    setProvider(next.provider);
    setModel(next.model);
    const providerDefault = providers.find((item) => item.provider === next.provider)?.timeout;
    setTimeoutSeconds(timeoutToSecondsInput(next.timeout ?? providerDefault));
  }, [
    settings?.provider,
    settings?.model,
    settings?.timeout,
    settings?.pending?.provider,
    settings?.pending?.model,
    settings?.pending?.timeout,
  ]);

  const selectedProvider = providers.find((item) => item.provider === provider) ?? providers[0];

  function selectProvider(nextProvider: string) {
    setConnectionCheckResult(null);
    setProvider(nextProvider);
    const nextStatus = providers.find((item) => item.provider === nextProvider);
    setModel(nextStatus?.model ?? "mock-llm");
    setTimeoutSeconds(timeoutToSecondsInput(nextStatus?.timeout));
  }

  const saveSettings = useMutation({
    mutationFn: async () => {
      const body: {
        provider: string;
        model: string;
        timeout?: string | null;
        geminiTimeout?: string | null;
        deepseekTimeout?: string | null;
        geminiApiKey?: string | null;
        deepseekApiKey?: string | null;
      } = {
        provider,
        model: model.trim(),
      };
      const timeout = timeoutPayload(provider, timeoutSeconds);
      if (timeout) {
        body.timeout = timeout;
        if (provider === "gemini") {
          body.geminiTimeout = timeout;
        }
        if (provider === "deepseek") {
          body.deepseekTimeout = timeout;
        }
      }
      if (provider === "gemini" && geminiApiKey.trim()) {
        body.geminiApiKey = geminiApiKey.trim();
      }
      if (provider === "deepseek" && deepseekApiKey.trim()) {
        body.deepseekApiKey = deepseekApiKey.trim();
      }
      return api.updateLlmSettings(body);
    },
    onSuccess: (result) => {
      setGeminiApiKey("");
      setDeepseekApiKey("");
      setConnectionCheckResult(null);
      void queryClient.invalidateQueries({ queryKey: ["llm-settings"] });
      void queryClient.invalidateQueries({ queryKey: ["health"] });
      onNotice(result.restartRequired
        ? "LLM 设置已保存。restartRequired=true，请重启 backend 生效。"
        : "LLM 设置已保存，当前配置已一致。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "LLM 设置保存失败。"),
  });

  const testConnection = useMutation({
    mutationFn: api.testLlmConnection,
    onMutate: () => {
      setConnectionCheckResult(null);
    },
    onSuccess: (result) => {
      setConnectionCheckResult(result);
    },
    onError: (error) => {
      const message = sanitizeConnectionCheckMessage(error instanceof Error ? error.message : "LLM provider connection check request failed.");
      setConnectionCheckResult({
        provider: settings?.provider ?? provider,
        model: settings?.model ?? (model.trim() || "-"),
        timeout: settings?.timeout ?? selectedProvider?.timeout,
        success: false,
        failureCategory: classifyConnectionCheckRequestError(message),
        messageSummary: message,
        keyConfigured: settings?.keyConfigured ?? false,
        keyStatus: settings?.keyStatus ?? "unknown",
      });
    },
  });

  return (
    <div className="grid gap-5">
      <section className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
        <Card>
          <CardHeader>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <CardTitle>LLM 设置</CardTitle>
                <CardDescription>本地配置写入 {settings?.localConfigPath ?? "config/devcontext.local.yml"}</CardDescription>
              </div>
              <Badge variant={settings?.restartRequired ? "warning" : "success"}>
                {settings?.restartRequired ? "restartRequired=true" : "active"}
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="grid gap-5">
            {loading ? (
              <EmptyState text="正在加载 LLM 设置。" />
            ) : (
              <>
                <div className="grid gap-3 md:grid-cols-4">
                  <MetricTile label="当前 provider" value={settings?.provider ?? "-"} tone={settings?.status === "ready" ? "success" : "warning"} />
                  <MetricTile label="当前 model" value={settings?.model ?? "-"} />
                  <MetricTile label="当前 timeout" value={formatTimeoutLabel(settings?.timeout)} />
                  <MetricTile label="key 状态" value={settings?.keyStatus ?? "-"} tone={settings?.keyConfigured ? "success" : "warning"} />
                </div>

                <div className="grid gap-4 rounded-lg border border-border bg-background p-4">
                  <div className="grid gap-3 md:grid-cols-[180px_minmax(0,1fr)_180px]">
                    <Label>
                      Provider
                      <select
                        className="mt-1 h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                        value={provider}
                        onChange={(event) => selectProvider(event.target.value)}
                      >
                        {providers.map((item) => (
                          <option key={item.provider} value={item.provider}>
                            {providerLabel(item.provider)}
                          </option>
                        ))}
                      </select>
                    </Label>
                    <Label>
                      Model
                      <Input
                        className="mt-1"
                        value={model}
                        onChange={(event) => {
                          setConnectionCheckResult(null);
                          setModel(event.target.value);
                        }}
                        placeholder={selectedProvider?.model ?? "model"}
                      />
                    </Label>
                    <Label>
                      Timeout (s)
                      <Input
                        className="mt-1"
                        type="number"
                        min={1}
                        step={1}
                        value={timeoutSeconds}
                        onChange={(event) => {
                          setConnectionCheckResult(null);
                          setTimeoutSeconds(event.target.value);
                        }}
                        placeholder={timeoutToSecondsInput(selectedProvider?.timeout) || "-"}
                        disabled={provider === "mock"}
                      />
                    </Label>
                  </div>

                  <div className="grid gap-3 md:grid-cols-2">
                    <Label>
                      Gemini API key
                      <Input
                        className="mt-1"
                        type="password"
                        value={geminiApiKey}
                        onChange={(event) => {
                          setConnectionCheckResult(null);
                          setGeminiApiKey(event.target.value);
                        }}
                        placeholder="留空则不改 key"
                        autoComplete="off"
                      />
                    </Label>
                    <Label>
                      DeepSeek API key
                      <Input
                        className="mt-1"
                        type="password"
                        value={deepseekApiKey}
                        onChange={(event) => {
                          setConnectionCheckResult(null);
                          setDeepseekApiKey(event.target.value);
                        }}
                        placeholder="留空则不改 key"
                        autoComplete="off"
                      />
                    </Label>
                  </div>

                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="flex flex-wrap gap-2">
                      <Badge variant={llmStatusBadge(selectedProvider?.status)}>{selectedProvider?.status ?? "pending"}</Badge>
                      <Badge variant={selectedProvider?.keyConfigured ? "success" : selectedProvider?.keyRequired ? "warning" : "secondary"}>
                        key {selectedProvider?.keyStatus ?? "unknown"}
                      </Badge>
                      <Badge variant="secondary">timeout {formatTimeoutLabel(selectedProvider?.timeout)}</Badge>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button variant="secondary" onClick={() => testConnection.mutate()} disabled={testConnection.isPending || loading}>
                        {testConnection.isPending ? <Loader2 className="size-4 animate-spin" /> : <Activity className="size-4" />}
                        测试连接
                      </Button>
                      <Button onClick={() => saveSettings.mutate()} disabled={saveSettings.isPending || !model.trim() || (provider !== "mock" && !timeoutSeconds.trim())}>
                        {saveSettings.isPending ? <Loader2 className="size-4 animate-spin" /> : <Settings2 className="size-4" />}
                        保存设置
                      </Button>
                    </div>
                  </div>

                  {testConnection.isPending || connectionCheckResult ? (
                    <LlmConnectionCheckResultPanel result={connectionCheckResult} loading={testConnection.isPending} />
                  ) : null}
                </div>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>状态</CardTitle>
            <CardDescription>active 与 pending 分开显示</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <div className="rounded-lg border border-border bg-background p-4">
              <div className="flex items-center gap-2 text-sm font-medium">
                <CheckCircle2 className="size-4 text-emerald-800" />
                当前生效
              </div>
              <div className="mt-3 grid gap-2 text-sm text-muted-foreground">
                <StateLine label="provider" value={settings?.provider ?? "-"} />
                <StateLine label="model" value={settings?.model ?? "-"} />
                <StateLine label="timeout" value={formatTimeoutLabel(settings?.timeout)} />
                <StateLine label="key" value={settings?.keyStatus ?? "-"} />
                <StateLine label="最近调用" value={settings?.lastCallStatus ?? "-"} />
                <StateLine label="最近错误" value={settings?.lastErrorType ?? "-"} />
              </div>
            </div>

            <div className={cn(
              "rounded-lg border p-4",
              settings?.restartRequired ? "border-amber-400/30 bg-amber-400/10" : "border-border bg-background",
            )}>
              <div className="flex items-center gap-2 text-sm font-medium">
                {settings?.restartRequired ? (
                  <AlertTriangle className="size-4 text-amber-800" />
                ) : (
                  <ShieldCheck className="size-4 text-sky-200" />
                )}
                本地 pending
              </div>
              {settings?.pending ? (
                <div className="mt-3 grid gap-2 text-sm text-muted-foreground">
                  <StateLine label="provider" value={settings.pending.provider} />
                  <StateLine label="model" value={settings.pending.model} />
                  <StateLine label="timeout" value={formatTimeoutLabel(settings.pending.timeout)} />
                  <StateLine label="key" value={settings.pending.keyStatus} />
                  <StateLine label="config" value={settings.pending.localConfigPath} />
                </div>
              ) : (
                <p className="mt-3 text-sm text-muted-foreground">没有待重启生效的本地 LLM 配置。</p>
              )}
              {settings?.restartRequired ? (
                <div className="mt-3 rounded-md border border-amber-400/25 bg-background px-3 py-2 text-sm text-amber-800">
                  重启 backend 后生效。
                </div>
              ) : null}
            </div>
          </CardContent>
        </Card>
      </section>
    </div>
  );
}

function LlmConnectionCheckResultPanel({
  result,
  loading,
}: {
  result: LlmConnectionCheckResult | null;
  loading: boolean;
}) {
  const success = Boolean(result?.success);
  const variant: BadgeVariant = loading ? "secondary" : success ? "success" : "danger";
  const borderClass = loading
    ? "border-border bg-muted/30"
    : success
      ? "border-emerald-400/25 bg-emerald-400/10"
      : "border-red-400/25 bg-red-400/10";

  return (
    <div className={cn("rounded-md border p-3", borderClass)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm font-medium">
          {loading ? (
            <Loader2 className="size-4 animate-spin text-muted-foreground" />
          ) : success ? (
            <CheckCircle2 className="size-4 text-emerald-800" />
          ) : (
            <AlertTriangle className="size-4 text-red-800" />
          )}
          连接测试
        </div>
        <Badge variant={variant}>{loading ? "running" : success ? "success" : "failure"}</Badge>
      </div>

      {loading ? (
        <p className="mt-3 text-sm text-muted-foreground">正在检查当前 LLM provider。</p>
      ) : result ? (
        <div className="mt-3 grid gap-2 text-sm text-muted-foreground">
          <StateLine label="provider" value={result.provider || "-"} />
          <StateLine label="model" value={result.model || "-"} />
          <StateLine label="timeout" value={formatTimeoutLabel(result.timeout)} />
          <StateLine label="success" value={result.success ? "true" : "false"} />
          <StateLine label="failureCategory" value={result.failureCategory || "-"} />
          <StateLine label="message" value={result.messageSummary || "-"} />
          <StateLine label="keyStatus" value={result.keyStatus || "unknown"} />
          <StateLine label="keyConfigured" value={result.keyConfigured ? "true" : "false"} />
        </div>
      ) : null}
    </div>
  );
}

function defaultLlmProviders(): LlmProviderStatus[] {
  return [
    { provider: "mock", model: "mock-llm", timeout: null, status: "ready", keyRequired: false, keyConfigured: false, keyStatus: "not_required" },
    { provider: "gemini", model: "gemini-2.0-flash", timeout: "60s", status: "missing_key", keyRequired: true, keyConfigured: false, keyStatus: "missing" },
    { provider: "deepseek", model: "deepseek-chat", timeout: "120s", status: "missing_key", keyRequired: true, keyConfigured: false, keyStatus: "missing" },
  ];
}

function timeoutToSecondsInput(timeout?: string | null) {
  if (!timeout) return "";
  const normalized = timeout.trim().toLowerCase();
  const simple = normalized.match(/^(\d+)(ms|s|m|h)?$/);
  if (simple) {
    const amount = Number(simple[1]);
    const unit = simple[2] ?? "s";
    if (unit === "ms") return String(Math.max(1, Math.ceil(amount / 1000)));
    if (unit === "m") return String(amount * 60);
    if (unit === "h") return String(amount * 3600);
    return String(amount);
  }
  const iso = normalized.match(/^pt(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/);
  if (iso) {
    const hours = Number(iso[1] ?? 0);
    const minutes = Number(iso[2] ?? 0);
    const seconds = Number(iso[3] ?? 0);
    return String(hours * 3600 + minutes * 60 + seconds);
  }
  return "";
}

function timeoutPayload(provider: string, timeoutSeconds: string) {
  if (provider === "mock") return null;
  const seconds = Number(timeoutSeconds);
  if (!Number.isFinite(seconds) || seconds <= 0) return null;
  return `${Math.round(seconds)}s`;
}

function formatTimeoutLabel(timeout?: string | null) {
  return timeout && timeout.trim() ? timeout : "-";
}

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    mock: "mock",
    gemini: "Gemini",
    deepseek: "DeepSeek",
  };
  return labels[provider] ?? provider;
}

function llmStatusBadge(status?: string): BadgeVariant {
  if (status === "ready") return "success";
  if (status === "missing_key") return "warning";
  if (status === "unsupported_provider" || status === "failed") return "danger";
  return "secondary";
}

function sanitizeConnectionCheckMessage(message: string) {
  const safe = (message || "LLM provider connection check request failed.")
    .replace(/(authorization\s*:\s*bearer\s+)[^\s,;]+/gi, "$1[masked]")
    .replace(/(api[-_ ]?key\s*[:=]\s*)[^\s,;}]+/gi, "$1[masked]")
    .replace(/AIza[0-9A-Za-z_-]{16,}/gi, "[masked]")
    .replace(/sk-[0-9A-Za-z_-]{16,}/gi, "[masked]")
    .replace(/\s+/g, " ")
    .trim();
  return safe.length > 300 ? safe.slice(0, 300) : safe;
}

function classifyConnectionCheckRequestError(message: string) {
  const normalized = message.toLowerCase();
  if (normalized.includes("timeout") || normalized.includes("timed out") || normalized.includes("abort")) return "LLM_TIMEOUT";
  if (normalized.includes("auth") || normalized.includes("unauthorized") || normalized.includes("forbidden") || normalized.includes("401") || normalized.includes("403")) {
    return "LLM_AUTH_FAILED";
  }
  if (normalized.includes("quota") || normalized.includes("rate limit") || normalized.includes("billing") || normalized.includes("429")) return "LLM_QUOTA_EXCEEDED";
  if (normalized.includes("parse") || normalized.includes("json")) return "LLM_PARSE_FAILED";
  if (normalized.includes("proxy")) return "LLM_PROXY_REQUIRED";
  if (normalized.includes("network") || normalized.includes("fetch") || normalized.includes("connection") || normalized.includes("dns") || normalized.includes("resolve")) {
    return "LLM_NETWORK_FAILED";
  }
  if (normalized.includes("not configured") || normalized.includes("missing key") || normalized.includes("unsupported provider")) {
    return "LLM_PROVIDER_NOT_CONFIGURED";
  }
  return "unknown";
}

function RunWorkspace({ initialRunId, project }: { initialRunId: number | null; project: Project | null }) {
  const [runId, setRunId] = useState(initialRunId ? String(initialRunId) : "");
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [runMeta, setRunMeta] = useState<string>("选择或输入 Run ID。");
  const runParams = useMemo(() => {
    const params = new URLSearchParams();
    params.set("limit", "40");
    if (project?.id) params.set("projectId", String(project.id));
    return params;
  }, [project?.id]);
  const runsQuery = useQuery({
    queryKey: ["agent-runs", runParams.toString()],
    queryFn: () => api.runs(runParams),
  });

  useEffect(() => {
    if (!initialRunId) return;
    setRunId(String(initialRunId));
    void load(initialRunId);
  }, [initialRunId]);

  async function load(id = asNumberOrNull(runId)) {
    if (!id) return;
    const [run, nextEvents] = await Promise.all([api.run(id), api.runEvents(id)]);
    setRunMeta(JSON.stringify(run, null, 2));
    setEvents(nextEvents);
  }

  return (
    <div className="grid grid-cols-1 gap-5 2xl:grid-cols-[minmax(0,380px)_minmax(0,1fr)]">
      <Card>
        <CardHeader>
          <CardTitle>运行历史</CardTitle>
          <CardDescription>{project ? `${project.name} 的最近运行。` : "最近所有运行。"}</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3">
          <div className="flex gap-2">
            <Input value={runId} onChange={(event) => setRunId(event.target.value)} placeholder="Run ID" />
            <Button variant="secondary" onClick={() => void load()}>加载</Button>
          </div>
          {runsQuery.isLoading ? (
            <EmptyState text="正在加载运行历史。" />
          ) : runsQuery.isError ? (
            <EmptyState text="运行历史加载失败。请确认后端已重启到最新版本。" />
          ) : runsQuery.data?.length ? (
            <div className="grid max-h-[420px] gap-2 overflow-auto pr-1">
              {runsQuery.data.map((run) => (
                <RunHistoryItem
                  key={run.id}
                  run={run}
                  active={String(run.id) === runId}
                  onOpen={() => {
                    setRunId(String(run.id));
                    void load(run.id);
                  }}
                />
              ))}
            </div>
          ) : (
            <EmptyState text="还没有运行记录。" />
          )}
          <pre className="max-h-72 overflow-auto rounded-md border border-border bg-background p-3 text-xs leading-6">{runMeta}</pre>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>事件流</CardTitle>
          <CardDescription>{events.length} 个事件。</CardDescription>
        </CardHeader>
        <CardContent>
          {events.length === 0 ? (
            <EmptyState text="还没有加载事件。" />
          ) : (
            <div className="grid divide-y divide-border">
              {events.map((event) => (
                <div key={event.id} className="grid grid-cols-[150px_minmax(0,1fr)] gap-4 py-4 max-md:grid-cols-1">
                  <div className="flex flex-wrap items-start gap-2">
                    <Badge variant={event.status === "success" ? "success" : "danger"}>{event.status}</Badge>
                    <Badge variant="secondary">Event #{event.id}</Badge>
                  </div>
                  <div className="min-w-0">
                    <div className="font-mono text-sm font-semibold">{event.eventType}</div>
                    <div className="mt-1 text-sm text-muted-foreground">{event.inputSummary}</div>
                    <div className="mt-1 text-sm">{event.outputSummary}</div>
                    {event.errorMessage ? <div className="mt-2 text-sm text-red-800">{event.errorMessage}</div> : null}
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function RunHistoryItem({ run, active, onOpen }: { run: AgentRun; active: boolean; onOpen: () => void }) {
  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        "rounded-lg border border-border bg-background p-3 text-left transition-colors hover:border-primary/40 hover:bg-secondary",
        active && "border-primary/60 bg-primary/10",
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-mono text-sm font-semibold">Run #{run.id}</span>
        <Badge variant={run.status === "success" ? "success" : run.status === "failed" ? "danger" : "warning"}>{run.status}</Badge>
      </div>
      <div className="mt-2 text-xs text-muted-foreground">{formatTime(run.createdAt)}</div>
      <div className="mt-2 flex flex-wrap gap-2">
        <Badge variant="secondary">{run.runType}</Badge>
        {run.modelName ? <Badge variant="secondary">{run.modelName}</Badge> : null}
        {typeof run.durationMs === "number" ? <Badge variant="secondary">{run.durationMs} ms</Badge> : null}
      </div>
      {run.errorMessage ? <p className="mt-2 line-clamp-2 text-xs text-red-800">{run.errorMessage}</p> : null}
    </button>
  );
}

function ImportProjectDialog({
  onSelectProject,
  onNotice,
  label = "导入项目",
}: {
  onSelectProject: (projectId: string) => void;
  onNotice: (message: string) => void;
  label?: string;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const directoryInputRef = useRef<HTMLInputElement | null>(null);
  const [form, setForm] = useState({ name: "", rootPath: "", defaultBranch: "main" });
  const [browserPickNote, setBrowserPickNote] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () => api.createProject(form),
    onSuccess: (project) => {
      onSelectProject(String(project.id));
      void queryClient.invalidateQueries({ queryKey: ["projects"] });
      setOpen(false);
      onNotice("项目已导入。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "项目导入失败。"),
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="secondary">
          <Plus className="size-4" />
          {label}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>导入本地项目</DialogTitle>
          <DialogDescription>填写名称，然后通过地址选择入口准备项目位置；默认不内置任何示例项目。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <Label>名称<Input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></Label>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" onClick={() => directoryInputRef.current?.click()}>
              <FileSearch className="size-4" />
              选择地址
            </Button>
          </div>
          <input
            ref={directoryInputRef}
            type="file"
            className="hidden"
            multiple
            {...{ webkitdirectory: "", directory: "" }}
            onChange={(event) => {
              const file = event.target.files?.[0];
              const relative = file?.webkitRelativePath || file?.name || "";
              const folderName = relative.split("/")[0] || "已选择地址";
              setForm((current) => ({ ...current, name: current.name || folderName, rootPath: "" }));
              setBrowserPickNote(`浏览器只提供目录名“${folderName}”，不会暴露真实绝对路径；因此本轮不会伪造地址。已有项目可继续从顶部列表选择。`);
            }}
          />
          {form.rootPath ? (
            <div className="rounded-md border border-border bg-muted p-3 text-xs text-muted-foreground">
              <div className="font-medium text-foreground">已选择地址</div>
              <div className="mt-1 break-all font-mono">{form.rootPath}</div>
            </div>
          ) : null}
          {browserPickNote ? <InlineState tone="empty" text={browserPickNote} /> : null}
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="ghost">取消</Button></DialogClose>
          <Button onClick={() => create.mutate()} disabled={create.isPending || !form.name.trim() || !form.rootPath}>
            {create.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            保存
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function HealthCard({ health, loading }: { health?: Health; loading: boolean }) {
  const provider = health?.llm?.provider ?? health?.llmProvider ?? "llm";
  const model = health?.llm?.model ?? health?.llmModel ?? "model";
  const keyStatus = health?.llm?.keyStatus ? ` · key ${health.llm.keyStatus}` : "";
  return (
    <div className="rounded-lg border border-border bg-background p-3">
      <div className="flex items-center gap-2 text-sm font-medium">
        <span className={cn("size-2 rounded-full", health ? "bg-emerald-300" : "bg-amber-300")} />
        {loading ? "检查服务中" : health ? "后端在线" : "后端离线"}
      </div>
      <div className="mt-3 grid gap-2 font-mono text-[11px] text-muted-foreground">
        <div className="rounded-md bg-muted px-2 py-1">{provider} · {model}{keyStatus}</div>
        <div className="rounded-md bg-muted px-2 py-1">vector · {health?.vectorProvider ?? "unknown"}</div>
      </div>
    </div>
  );
}

function NavItem({ icon: Icon, label, active, onClick }: { icon: LucideIcon; label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      className={cn(
        "flex h-10 items-center gap-3 rounded-md px-3 text-sm text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground",
        active && "bg-secondary text-foreground",
      )}
      onClick={onClick}
    >
      <Icon className="size-4" />
      {label}
    </button>
  );
}

function Metric({ label, value, tone }: { label: string; value: string; tone: "good" | "warn" | "bad" | "neutral" }) {
  return (
    <div className="rounded-md border border-border bg-background p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={cn("mt-2 text-2xl font-semibold", tone === "good" && "text-emerald-800", tone === "warn" && "text-amber-800", tone === "bad" && "text-red-800")}>{value}</div>
    </div>
  );
}

function TaskCard({ icon: Icon, title, text, disabled, onClick }: { icon: LucideIcon; title: string; text: string; disabled?: boolean; onClick: () => void }) {
  return (
    <button
      disabled={disabled}
      onClick={onClick}
      className="rounded-lg border border-border bg-card p-5 text-left transition-colors hover:border-primary/40 hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
    >
      <Icon className="size-5 text-primary" />
      <div className="mt-4 font-semibold">{title}</div>
      <div className="mt-2 text-sm leading-6 text-muted-foreground">{text}</div>
      <div className="mt-4 flex items-center gap-1 text-sm text-primary">打开 <ChevronRight className="size-4" /></div>
    </button>
  );
}

function StateLine({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-4 text-sm">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <span className="min-w-0 break-all text-right">{value}</span>
    </div>
  );
}

type BadgeVariant = "default" | "secondary" | "success" | "warning" | "danger";

function withUpdatedReviewIssue(detail: ReviewDetail, updated: ReviewIssue): ReviewDetail {
  const issues = detail.issues.map((issue) => (issue.id === updated.id ? updated : issue));
  return {
    ...detail,
    issues,
    outcomeSummary: deriveReviewOutcomeSummary(issues),
  };
}

function reviewOutcomeSummary(detail: ReviewDetail): ReviewOutcomeSummary {
  return detail.outcomeSummary ?? deriveReviewOutcomeSummary(detail.issues);
}

function deriveReviewOutcomeSummary(issues: ReviewIssue[]): ReviewOutcomeSummary {
  const summary: ReviewOutcomeSummary = {
    total: issues.length,
    pending: 0,
    accepted: 0,
    fixed: 0,
    falsePositive: 0,
    rejected: 0,
    ignored: 0,
    positiveOutcome: 0,
    negativeOutcome: 0,
  };

  for (const issue of issues) {
    const status = (issue.status ?? "pending").trim().toLowerCase();
    if (status === "pending") summary.pending += 1;
    if (status === "accepted") summary.accepted += 1;
    if (status === "fixed") summary.fixed += 1;
    if (status === "false_positive") summary.falsePositive += 1;
    if (status === "rejected") summary.rejected += 1;
    if (status === "ignored") summary.ignored += 1;
  }
  summary.positiveOutcome = summary.accepted + summary.fixed;
  summary.negativeOutcome = summary.falsePositive + summary.rejected + summary.ignored;
  return summary;
}

const PRIMARY_EVIDENCE_GROUP_ORDER = [
  "source_code",
  "configuration",
  "sql_schema_mapper",
  "test",
  "benchmark_review_runtime_report",
];

function auxiliaryContextDocuments(documents: ContextDocumentStatus[]) {
  return documents.filter((document) => document.generated || document.path.startsWith(".ai/"));
}

function primaryEvidenceGroups(coverage?: ProjectEvidenceCoverageSummary) {
  const order = new Map(PRIMARY_EVIDENCE_GROUP_ORDER.map((key, index) => [key, index]));
  return (coverage?.sourceGroups ?? [])
    .filter((group) => group.primaryEvidence)
    .sort((left, right) => {
      const leftOrder = order.get(left.groupKey) ?? 99;
      const rightOrder = order.get(right.groupKey) ?? 99;
      return leftOrder - rightOrder || sourceGroupLabel(left.groupKey, left.label).localeCompare(sourceGroupLabel(right.groupKey, right.label), "zh-CN");
    });
}

function primaryEvidenceStats(coverage?: ProjectEvidenceCoverageSummary) {
  const groups = primaryEvidenceGroups(coverage);
  const total = groups.length;
  const present = groups.filter((group) => group.present).length;
  return {
    total,
    present,
    missing: total - present,
  };
}

function evidenceCoverageTone(stats: { total: number; present: number; missing: number }): "good" | "warn" | "bad" | "neutral" {
  if (!stats.total) return "neutral";
  if (stats.missing === 0) return "good";
  if (stats.present > 0) return "warn";
  return "bad";
}

function findKnowledgeSourceForProject(sources: KnowledgeSource[], project: Project | null) {
  if (!project) return null;
  const projectRoot = comparablePath(project.rootPath);
  return sources.find((source) => comparablePath(source.rootPath) === projectRoot) ?? null;
}

function comparablePath(path: string) {
  return path.replaceAll("\\", "/").replace(/\/+$/, "").trim().toLowerCase();
}

function pathSummary(path: string) {
  const normalized = path.replaceAll("\\", "/").replace(/\/+$/, "");
  const parts = normalized.split("/").filter(Boolean);
  if (parts.length <= 2) return normalized || "-";
  return `${parts.at(-2)}/${parts.at(-1)}`;
}

function readIndexState(projectId: number | null): SavedIndexState | null {
  if (!projectId) return null;
  const state = readLocalState<Record<string, SavedIndexState>>("devcontext.knowledgeIndexState.v1", {});
  return state[String(projectId)] ?? null;
}

function writeIndexState(next: SavedIndexState) {
  const state = readLocalState<Record<string, SavedIndexState>>("devcontext.knowledgeIndexState.v1", {});
  state[String(next.projectId)] = next;
  localStorage.setItem("devcontext.knowledgeIndexState.v1", JSON.stringify(state));
}

function readLocalState<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) as T : fallback;
  } catch {
    return fallback;
  }
}

function clearProjectLocalState(projectId: number) {
  const indexState = readLocalState<Record<string, SavedIndexState>>("devcontext.knowledgeIndexState.v1", {});
  delete indexState[String(projectId)];
  localStorage.setItem("devcontext.knowledgeIndexState.v1", JSON.stringify(indexState));
  const legacyContextState = readLocalState<Record<string, unknown>>("devcontext.contextGenerationState.v1", {});
  delete legacyContextState[String(projectId)];
  localStorage.setItem("devcontext.contextGenerationState.v1", JSON.stringify(legacyContextState));
}

function resolveIndexState(project: Project | null, source: KnowledgeSource | null, saved: SavedIndexState | null): SavedIndexState {
  if (!project) return { projectId: 0, status: "idle" };
  if (source?.status === "indexed") {
    return {
      projectId: project.id,
      sourceId: source.id,
      status: "completed",
      completedAt: source.updatedAt,
    };
  }
  if (saved?.projectId === project.id) return saved;
  return { projectId: project.id, sourceId: source?.id, status: "idle" };
}

function operationBadge(status: OperationStatus): BadgeVariant {
  if (status === "completed") return "success";
  if (status === "failed") return "danger";
  if (status === "running" || status === "queued") return "warning";
  return "secondary";
}

function operationLabel(status: OperationStatus) {
  const labels: Record<OperationStatus, string> = {
    idle: "未开始",
    queued: "排队中",
    running: "进行中",
    completed: "已完成",
    failed: "失败",
  };
  return labels[status];
}

function indexStatusText(state: SavedIndexState, source: KnowledgeSource | null) {
  if (state.status === "completed") {
    return source?.updatedAt ? `索引已完成，最后更新时间 ${formatTime(source.updatedAt)}。` : "索引已完成。";
  }
  if (state.status === "running" || state.status === "queued") return "正在建立索引，刷新页面后会继续显示进行中状态。";
  if (state.status === "failed") return state.error ?? "索引失败，可重试。";
  return "未建立索引。为当前项目建立可检索的源码、配置、SQL、测试、报告和文档索引。";
}

function indexStatusMeta(state: SavedIndexState, source: KnowledgeSource | null) {
  if (state.status === "completed") {
    if (typeof state.documentsIndexed === "number" || typeof state.chunksIndexed === "number") {
      return `${state.documentsIndexed ?? "-"} docs · ${state.chunksIndexed ?? "-"} chunks`;
    }
    return source?.updatedAt ? formatTime(source.updatedAt) : "ready";
  }
  if (state.status === "running" || state.status === "queued") return state.startedAt ? `started ${formatTime(state.startedAt)}` : "running";
  if (state.status === "failed") return state.failedAt ? formatTime(state.failedAt) : "retry";
  return source ? source.status ?? "created" : "no source";
}

function evidenceEvaluationBadge(evaluation: EvidenceEvaluation): BadgeVariant {
  if (evaluation.noAnswerRequired || !evaluation.sufficient) return "warning";
  if (evaluation.status === "sufficient" || evaluation.answerGuardDecision === "answer_allowed") return "success";
  return "secondary";
}

function evidenceEvaluationLabel(evaluation: EvidenceEvaluation) {
  if (evaluation.noAnswerRequired) return "no answer required";
  if (!evaluation.sufficient) return "insufficient evidence";
  return "evidence checked";
}

function evidenceEvaluationToneClass(evaluation: EvidenceEvaluation) {
  if (evaluation.noAnswerRequired || !evaluation.sufficient) return "border-amber-700/20 bg-amber-600/10";
  return "border-emerald-700/20 bg-emerald-700/8";
}

function qualityMeta(level: string): {
  label: string;
  description: string;
  badge: BadgeVariant;
  icon: LucideIcon;
  iconClass: string;
} {
  if (level === "high") {
    return {
      label: "辅助资料完整",
      description: "生成文档和人工文档较完整，可帮助概览项目，但实现结论仍应优先查看原始证据。",
      badge: "success",
      icon: CheckCircle2,
      iconClass: "border-emerald-400/25 bg-emerald-400/10 text-emerald-800",
    };
  }
  if (level === "medium") {
    return {
      label: "需要补充",
      description: "辅助文档可用，但仍有缺失或 TODO。适合做概览，关键结论需要回到源码、配置、SQL、测试或报告确认。",
      badge: "warning",
      icon: ShieldCheck,
      iconClass: "border-amber-400/25 bg-amber-400/10 text-amber-800",
    };
  }
  return {
    label: "辅助资料不足",
    description: "辅助文档还没有被确认。请优先查看上方原始证据覆盖，再决定是否补充 `.ai` 文档。",
    badge: "danger",
    icon: AlertTriangle,
    iconClass: "border-red-400/25 bg-red-400/10 text-red-800",
  };
}

function qualityIssueBadge(severity?: string): BadgeVariant {
  if (severity === "error") return "danger";
  if (severity === "warning") return "warning";
  return "secondary";
}

function qualityIssueLabel(severity?: string) {
  if (severity === "error") return "阻塞";
  if (severity === "warning") return "待确认";
  return "提示";
}

function sourceGroupLabel(groupKey: string, fallback?: string) {
  const labels: Record<string, string> = {
    source_code: "源码",
    configuration: "配置",
    sql_schema_mapper: "SQL / schema / mapper",
    test: "测试",
    handwritten_documentation: "手写文档",
    generated_documentation: "生成文档",
    benchmark_review_runtime_report: "benchmark / review / runtime report",
    project_root: "项目根目录",
    project_file_scan: "项目文件扫描",
  };
  return labels[groupKey] ?? fallback ?? groupKey;
}

function sourceKindLabel(kind: string) {
  const labels: Record<string, string> = {
    api_surface: "API",
    benchmark_report: "benchmark report",
    cache: "cache",
    ci_pipeline: "CI",
    code_structure: "code map",
    configuration: "config",
    data_access: "mapper",
    data_schema: "SQL/schema",
    deployment: "deploy",
    documentation: "docs",
    message_queue: "queue",
    observability: "observability",
    security: "security",
    source_code: "source",
    test_artifact: "test",
  };
  return labels[kind] ?? kind;
}

function sourceReliabilityLabel(reliability: string) {
  const labels: Record<string, string> = {
    primary: "strong",
    secondary: "supporting",
    derived: "derived",
  };
  return labels[reliability] ?? reliability;
}

function MiniProcess({ icon: Icon, title, text }: { icon: LucideIcon; title: string; text: string }) {
  return (
    <div className="rounded-lg border border-border bg-background p-4">
      <Icon className="size-4 text-primary" />
      <div className="mt-3 font-medium">{title}</div>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">{text}</p>
    </div>
  );
}

function TextBlock({ title, text, className }: { title: string; text: string; className?: string }) {
  return (
    <div className={cn("min-w-0", className)}>
      <div className="mb-1 text-xs font-medium text-muted-foreground">{title}</div>
      <p className="break-words text-sm leading-6">{text}</p>
    </div>
  );
}

function EmptyState({ text }: { text: string }) {
  return (
    <div className="grid min-h-28 place-items-center rounded-lg border border-dashed border-border bg-background p-6 text-center text-sm text-muted-foreground">
      {text}
    </div>
  );
}

function severityBadge(severity?: string) {
  if (severity === "critical") return "danger";
  if (severity === "warning") return "warning";
  return "secondary";
}

function statusBadge(status?: string) {
  if (status === "accepted" || status === "fixed" || status === "active" || status === "indexed") return "success";
  if (status === "false_positive" || status === "deprecated" || status === "rejected") return "warning";
  if (status === "failed") return "danger";
  return "secondary";
}

function reviewSignalToneClass(tone: "confirmed" | "suppressed" | "neutral") {
  if (tone === "confirmed") return "border-emerald-400/20 bg-emerald-400/5";
  if (tone === "suppressed") return "border-amber-400/20 bg-amber-400/5";
  return "border-border bg-muted/20";
}

function outcomeMetricToneClass(tone: "good" | "warn" | "bad" | "neutral") {
  if (tone === "good") return "border-emerald-400/20 bg-emerald-400/5";
  if (tone === "warn") return "border-amber-400/20 bg-amber-400/5";
  if (tone === "bad") return "border-red-400/20 bg-red-400/5";
  return "border-border bg-muted/20";
}

function reviewSignalTypeLabel(type: string) {
  const labels: Record<string, string> = {
    confirmed_issue_pattern: "confirmed_issue_pattern",
    false_positive_pattern: "false_positive_pattern",
  };
  return labels[type] ?? type;
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    accepted: "采纳",
    partially_reused: "部分复用",
    rejected: "拒绝",
    false_positive: "误报",
  };
  return labels[status] ?? status;
}

function coverageEntries(coverage?: EvidenceCoverageReport["coverage"] | null): Array<[KnowledgeEvidenceType, number]> {
  if (!coverage) return [];
  return (Object.entries(coverage) as Array<[KnowledgeEvidenceType, number]>)
    .filter(([, count]) => typeof count === "number" && count > 0)
    .sort((left, right) => right[1] - left[1] || evidenceTypeLabel(left[0]).localeCompare(evidenceTypeLabel(right[0]), "zh-CN"));
}

function evidenceTypeLabel(type: KnowledgeEvidenceType | string) {
  const labels: Partial<Record<KnowledgeEvidenceType, string>> = {
    GENERATED_DOC: "生成文档",
    MANUAL_DOC: "人工文档",
    CODE_MAP: "代码地图",
    SQL_SCHEMA: "SQL",
    MAPPER: "Mapper",
    CONFIG: "配置",
    DEPLOYMENT: "部署",
    OBSERVABILITY: "监控",
    TEST: "测试",
    BENCHMARK: "压测",
    CI: "CI",
    API_CONTROLLER: "接口",
    SERVICE_CODE: "服务代码",
    QUEUE: "队列",
    CACHE: "缓存",
    SECURITY: "安全",
  };
  return labels[type as KnowledgeEvidenceType] ?? type;
}

function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function lines(value: string) {
  return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
}

function round(value?: number) {
  if (typeof value !== "number") return "-";
  return Math.round(value * 1000) / 1000;
}

function mutationErrorMessage(error: unknown) {
  if (!error) return null;
  return error instanceof Error ? error.message : "请求失败，请确认后端接口可用。";
}

export default App;
