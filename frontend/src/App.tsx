import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  BookOpen,
  Boxes,
  Brain,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Database,
  FileCode2,
  FileSearch,
  GitBranch,
  GitPullRequest,
  History,
  LayoutDashboard,
  Loader2,
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
  KnowledgeEvidenceType,
  KnowledgeIndexResult,
  KnowledgeQueryPlan,
  KnowledgeSearchResponse,
  KnowledgeSource,
  LlmProviderStatus,
  LlmSettings,
  Project,
  ProjectContextStatus,
  ContextGenerationResult,
  ContextQualitySummary,
  RagAnswerResult,
  ReviewDetail,
  ReviewIssue,
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

const lifeServicePath = "C:\\Users\\lenovo\\Documents\\Codex\\2026-05-20\\life-service";
const devContextPath = "D:\\CodeX\\DevContext";

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
  const contextQuery = useQuery({
    queryKey: ["project-context", activeProjectId],
    queryFn: () => api.contextStatus(activeProjectId as number),
    enabled: Boolean(activeProjectId),
  });

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
                contextStatus={contextQuery.data}
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
              <KnowledgeWorkspace sources={sourcesQuery.data ?? []} onNotice={show} onOpenRun={openRun} />
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

  return (
    <header className="border-b border-border bg-background/95 px-5 py-4 lg:px-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="text-xs font-medium text-muted-foreground">当前空间</div>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">{titleMap[activeView]}</h1>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <Label className="w-80 max-w-full">
            项目
            <select
              className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
              value={selectedProjectId}
              onChange={(event) => onSelectProject(event.target.value)}
            >
              <option value="">未选择项目</option>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name} · {project.defaultBranch}
                </option>
              ))}
            </select>
          </Label>
          <ImportProjectDialog onSelectProject={onSelectProject} onNotice={onNotice} />
          <Button size="icon" variant="secondary" onClick={onRefresh} aria-label="刷新">
            <RefreshCw className="size-4" />
          </Button>
        </div>
      </div>
    </header>
  );
}

function Overview({
  project,
  contextStatus,
  decisions,
  sources,
  onGo,
  onNotice,
  onSelectProject,
}: {
  project: Project | null;
  contextStatus?: ProjectContextStatus;
  decisions: DecisionCard[];
  sources: KnowledgeSource[];
  onGo: (view: View) => void;
  onNotice: (message: string) => void;
  onSelectProject: (projectId: string) => void;
}) {
  const queryClient = useQueryClient();
  const [contextMode, setContextMode] = useState<"refresh" | "missing">("refresh");
  const [overwriteManual, setOverwriteManual] = useState(false);
  const [generationResult, setGenerationResult] = useState<ContextGenerationResult | null>(null);
  const generateContext = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      return api.generateContext(project.id, {
        overwriteGenerated: contextMode === "refresh",
        overwriteManual,
      });
    },
    onSuccess: (result) => {
      setGenerationResult(result);
      void queryClient.invalidateQueries({ queryKey: ["project-context", project?.id] });
      const written = result.generatedFiles.length + result.manualCreatedFiles.length;
      const skipped = result.generatedSkippedFiles.length + result.manualSkippedFiles.length;
      onNotice(`上下文更新完成：写入 ${written} 个，跳过 ${skipped} 个。`);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "上下文生成失败。"),
  });

  const existingDocs = contextStatus?.documents.filter((item) => item.exists).length ?? 0;
  const totalDocs = contextStatus?.documents.length ?? 0;
  const quality = contextStatus?.quality;

  return (
    <div className="grid gap-5">
      <section className="grid grid-cols-[minmax(0,1fr)_340px] gap-5 xl:grid-cols-[minmax(0,1fr)_360px] max-xl:grid-cols-1">
        <div className="rounded-lg border border-border bg-card p-5">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <div className="text-sm text-muted-foreground">项目</div>
              <h2 className="mt-1 text-xl font-semibold">{project?.name ?? "未选择项目"}</h2>
              <div className="mt-2 max-w-3xl break-all font-mono text-xs text-muted-foreground">
                {project?.rootPath ?? "选择或导入一个项目后开始工作"}
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <ProjectSettingsDialog project={project} onNotice={onNotice} onSelectProject={onSelectProject} />
              <Button disabled={!project || generateContext.isPending} onClick={() => generateContext.mutate()}>
                {generateContext.isPending ? <Loader2 className="size-4 animate-spin" /> : <FileCode2 className="size-4" />}
                {contextMode === "refresh" ? "全量刷新" : "补齐缺失"}
              </Button>
            </div>
          </div>
          <div className="mt-5 grid gap-4 rounded-md border border-border bg-background p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="text-sm font-medium">上下文更新模式</div>
                <p className="mt-1 text-sm leading-6 text-muted-foreground">
                  生成资产默认可覆盖，人工文档默认保护。再次生成时先选模式，再执行。
                </p>
              </div>
              <div className="flex rounded-md border border-border bg-card p-1">
                <button
                  type="button"
                  className={cn(
                    "h-8 rounded px-3 text-sm text-muted-foreground",
                    contextMode === "refresh" && "bg-primary text-primary-foreground",
                  )}
                  onClick={() => setContextMode("refresh")}
                >
                  全量刷新生成资产
                </button>
                <button
                  type="button"
                  className={cn(
                    "h-8 rounded px-3 text-sm text-muted-foreground",
                    contextMode === "missing" && "bg-primary text-primary-foreground",
                  )}
                  onClick={() => setContextMode("missing")}
                >
                  只补缺失资产
                </button>
              </div>
            </div>
            <label className="flex items-start gap-3 text-sm">
              <input
                type="checkbox"
                className="mt-1 size-4 accent-sky-400"
                checked={overwriteManual}
                onChange={(event) => setOverwriteManual(event.target.checked)}
              />
              <span>
                覆盖人工文档
                <span className="block text-muted-foreground">
                  默认关闭，避免覆盖 `.ai/manual/*` 中的业务背景、偏好、决策和踩坑记录。
                </span>
              </span>
            </label>
          </div>
          <div className="mt-5 grid grid-cols-4 gap-3 max-lg:grid-cols-2 max-md:grid-cols-1">
            <Metric label="上下文资产" value={totalDocs ? `${existingDocs}/${totalDocs}` : "未生成"} tone={existingDocs ? "good" : "warn"} />
            <Metric label="可信度" value={quality ? `${quality.score}` : "-"} tone={qualityTone(quality?.level)} />
            <Metric label="活跃决策" value={`${decisions.length}`} tone={decisions.length ? "good" : "neutral"} />
            <Metric label="知识源" value={`${sources.length}`} tone={sources.length ? "good" : "neutral"} />
          </div>
        </div>

        <div className="rounded-lg border border-border bg-card p-5">
          <div className="text-sm font-medium">运行环境</div>
          <div className="mt-4 grid gap-3">
            <StateLine label="默认分支" value={project?.defaultBranch ?? "-"} />
            <StateLine label="语言" value={project?.language ?? "-"} />
            <StateLine label="框架" value={project?.framework ?? "-"} />
          </div>
        </div>
      </section>

      <ContextGenerationPanel result={generationResult} />

      <ContextQualityPanel quality={quality} />

      <section className="grid grid-cols-3 gap-5 max-xl:grid-cols-1">
        <TaskCard
          icon={GitPullRequest}
          title="审查当前分支"
          text="根据 base/compare 分支或粘贴 diff 生成 ReviewIssue。"
          disabled={!project}
          onClick={() => onGo("review")}
        />
        <TaskCard
          icon={Brain}
          title="复用历史决策"
          text="检索 DecisionCard，让 AI 判断当前场景是否适用。"
          disabled={!project}
          onClick={() => onGo("decisions")}
        />
        <TaskCard
          icon={BookOpen}
          title="查询项目知识"
          text="索引项目文档和 AI 上下文资产，进行带引用问答。"
          disabled={!project}
          onClick={() => onGo("knowledge")}
        />
      </section>

      <section className="rounded-lg border border-border bg-card">
        <div className="flex items-center justify-between border-b border-border p-4">
          <div>
            <h3 className="font-semibold">上下文资产</h3>
            <p className="mt-1 text-sm text-muted-foreground">这些文件会作为后续审查和问答的项目事实。</p>
          </div>
        </div>
        <div className="grid divide-y divide-border">
          {!contextStatus ? (
            <EmptyState text="选择项目后可以查看上下文状态。" />
          ) : (
            contextStatus.documents.map((doc) => (
              <div key={doc.path} className="grid grid-cols-[160px_minmax(0,1fr)_120px] gap-4 px-4 py-3 text-sm max-md:grid-cols-1">
                <span className="font-medium">{doc.type}</span>
                <span className="min-w-0 truncate font-mono text-xs text-muted-foreground">{doc.path}</span>
                <div className="flex flex-wrap justify-end gap-2 max-md:justify-start">
                  <Badge variant={doc.exists ? "success" : "warning"}>{doc.exists ? doc.status : "missing"}</Badge>
                  {quality?.issues.some((issue) => issue.path === doc.path) ? <Badge variant="warning">待确认</Badge> : null}
                </div>
              </div>
            ))
          )}
        </div>
      </section>
    </div>
  );
}

function ProjectSettingsDialog({
  project,
  onNotice,
  onSelectProject,
}: {
  project: Project | null;
  onNotice: (message: string) => void;
  onSelectProject: (projectId: string) => void;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", rootPath: "", defaultBranch: "main" });

  useEffect(() => {
    setForm({
      name: project?.name ?? "",
      rootPath: project?.rootPath ?? "",
      defaultBranch: project?.defaultBranch ?? "main",
    });
  }, [project?.id, project?.name, project?.rootPath, project?.defaultBranch]);

  const updateProject = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      return api.updateProject(project.id, form);
    },
    onSuccess: (updated) => {
      void queryClient.invalidateQueries({ queryKey: ["projects"] });
      void queryClient.invalidateQueries({ queryKey: ["project-context", updated.id] });
      onSelectProject(String(updated.id));
      setOpen(false);
      onNotice("项目信息已更新。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "项目更新失败。"),
  });

  const deleteProject = useMutation({
    mutationFn: async () => {
      if (!project) throw new Error("请先选择项目。");
      return api.deleteProject(project.id);
    },
    onSuccess: () => {
      onSelectProject("");
      void queryClient.invalidateQueries({ queryKey: ["projects"] });
      void queryClient.invalidateQueries({ queryKey: ["project-context"] });
      setOpen(false);
      onNotice("项目登记已移除，本地项目文件不会被删除。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "项目删除失败。"),
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="secondary" disabled={!project}>
          <Settings2 className="size-4" />
          项目设置
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>项目设置</DialogTitle>
          <DialogDescription>修改 DevContext 中的项目登记，不直接修改本地项目文件。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <Label>名称<Input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></Label>
          <Label>本地路径<Input value={form.rootPath} onChange={(event) => setForm({ ...form, rootPath: event.target.value })} /></Label>
          <Label>默认分支<Input value={form.defaultBranch} onChange={(event) => setForm({ ...form, defaultBranch: event.target.value })} /></Label>
          <div className="rounded-md border border-amber-400/20 bg-amber-400/10 p-3 text-sm leading-6 text-amber-100">
            删除项目只会移除 DevContext 工作台记录、上下文记录和相关历史记录，不会删除磁盘目录。
          </div>
        </div>
        <DialogFooter>
          <Button
            variant="ghost"
            className="text-red-200 hover:text-red-100"
            disabled={!project || deleteProject.isPending}
            onClick={() => deleteProject.mutate()}
          >
            {deleteProject.isPending ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
            删除登记
          </Button>
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

function ContextQualityPanel({ quality }: { quality?: ContextQualitySummary }) {
  if (!quality) {
    return (
      <section className="rounded-lg border border-border bg-card p-5">
        <div className="flex items-start gap-3">
          <FileSearch className="mt-0.5 size-5 text-muted-foreground" />
          <div>
            <h3 className="font-semibold">上下文可信度</h3>
            <p className="mt-1 text-sm leading-6 text-muted-foreground">生成上下文后，这里会显示 AI 文档是否可以直接用于问答和审查。</p>
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
              <h3 className="font-semibold">上下文可信度</h3>
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
            <div className="text-muted-foreground">文档</div>
            <div className="mt-1 text-base font-semibold">{quality.existingDocuments}/{quality.totalDocuments}</div>
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
        <div className="mt-5 rounded-md border border-emerald-400/20 bg-emerald-400/10 p-3 text-sm text-emerald-100">
          当前上下文资产没有发现缺失或 TODO，适合进入知识库索引和代码审查。
        </div>
      )}
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
    onSuccess: async (result) => {
      const next = await api.review(result.reviewId);
      setReviewId(String(result.reviewId));
      setDetail(next);
      void queryClient.invalidateQueries({ queryKey: ["project-reviews", project?.id] });
      onNotice(result.diffTruncated ? `审查完成，解析出 ${next.issues.length} 个问题。注意：diff 已截断，请查看追踪确认范围。` : `审查完成，解析出 ${next.issues.length} 个问题。`);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "代码审查失败。"),
  });

  const updateIssue = useMutation({
    mutationFn: ({ issueId, status }: { issueId: number; status: string }) => api.updateReviewIssue(issueId, { status }),
    onSuccess: (updated) => {
      setDetail((current) =>
        current
          ? {
              ...current,
              issues: current.issues.map((issue) => (issue.id === updated.id ? updated : issue)),
            }
          : current,
      );
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
    const next = await api.review(id);
    setReviewId(String(id));
    setDetail(next);
  }

  async function loadReviewById(id: number) {
    const next = await api.review(id);
    setDetail(next);
    setReviewId(String(id));
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
      </section>

      <section className="grid min-w-0 gap-5 overflow-hidden">
        <ReviewResultPanel detail={detail} onOpenRun={onOpenRun} onUpdateIssue={(issueId, status) => updateIssue.mutate({ issueId, status })} />
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
      <div className="rounded-lg border border-amber-400/30 bg-amber-400/10 p-4 text-sm leading-6 text-amber-100">
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
                    <p className="mt-2 rounded-md border border-amber-400/30 bg-amber-400/10 px-2 py-1 text-xs leading-5 text-amber-100">
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
      {record.summary ? <p className="mt-2 line-clamp-2 text-sm leading-5 text-muted-foreground">{record.summary}</p> : null}
    </button>
  );
}

function ReviewResultPanel({
  detail,
  onOpenRun,
  onUpdateIssue,
}: {
  detail: ReviewDetail | null;
  onOpenRun: (runId: number) => void;
  onUpdateIssue: (issueId: number, status: string) => void;
}) {
  const criticalCount = detail?.issues.filter((issue) => issue.severity === "critical").length ?? 0;
  const warningCount = detail?.issues.filter((issue) => issue.severity === "warning").length ?? 0;

  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader className="min-w-0 max-md:flex-col max-md:items-start">
        <div className="min-w-0">
          <CardTitle>审查结果</CardTitle>
          <CardDescription>{detail ? detail.review.summary || "结构化 ReviewIssue" : "提交审查后显示问题列表。"}</CardDescription>
        </div>
        {detail?.review.runId ? (
          <Button variant="secondary" onClick={() => onOpenRun(detail.review.runId as number)}>
            <Activity className="size-4" />
            查看追踪
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
            {detail.issues.length === 0 ? (
              <EmptyState text="这次审查没有解析出问题。" />
            ) : (
              <div className="grid gap-3">
                {detail.issues.map((issue) => (
                  <ReviewIssueCard key={issue.id} issue={issue} onUpdateStatus={onUpdateIssue} />
                ))}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ReviewIssueCard({ issue, onUpdateStatus }: { issue: ReviewIssue; onUpdateStatus: (issueId: number, status: string) => void }) {
  return (
    <article className="min-w-0 overflow-hidden rounded-lg border border-border bg-background p-4">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Badge variant={severityBadge(issue.severity)}>{issue.severity}</Badge>
        <Badge variant={statusBadge(issue.status)}>{issue.status ?? "pending"}</Badge>
        {issue.confidence ? <Badge variant="secondary">{issue.confidence}</Badge> : null}
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
      <div className="mt-4 flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" onClick={() => onUpdateStatus(issue.id, "accepted")}>采纳</Button>
        <Button size="sm" variant="secondary" onClick={() => onUpdateStatus(issue.id, "false_positive")}>误报</Button>
        <Button size="sm" variant="secondary" onClick={() => onUpdateStatus(issue.id, "fixed")}>已修复</Button>
        <Button size="sm" variant="ghost" onClick={() => onUpdateStatus(issue.id, "rejected")}>拒绝</Button>
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
              <Button variant="secondary" onClick={() => onOpenRun(adviceResult.runId)}>
                <Activity className="size-4" />
                查看追踪
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
  sources,
  onNotice,
  onOpenRun,
}: {
  sources: KnowledgeSource[];
  onNotice: (message: string) => void;
  onOpenRun: (runId: number) => void;
}) {
  const queryClient = useQueryClient();
  const [sourceForm, setSourceForm] = useState({ name: "life-service AI Docs", rootPath: lifeServicePath, sourceType: "project_ai_docs" });
  const [askForm, setAskForm] = useState({ sourceId: "", query: "这个项目的核心流程是什么？", topK: "5" });
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResponse | null>(null);
  const [answerResult, setAnswerResult] = useState<RagAnswerResult | null>(null);
  const [indexResult, setIndexResult] = useState<KnowledgeIndexResult | null>(null);
  const [indexingSourceId, setIndexingSourceId] = useState<number | null>(null);
  const [lastIndexedAt, setLastIndexedAt] = useState<string | null>(null);

  const createSource = useMutation({
    mutationFn: () => api.createSource(sourceForm),
    onSuccess: (source) => {
      setAskForm((current) => ({ ...current, sourceId: String(source.id) }));
      void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
      onNotice("知识源已添加。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "知识源创建失败。"),
  });

  const indexSource = useMutation({
    mutationFn: (sourceId: number) => api.indexSource(sourceId),
    onMutate: (sourceId) => {
      setIndexingSourceId(sourceId);
      setIndexResult(null);
      setSearchResult(null);
      setAnswerResult(null);
      onNotice("开始索引知识源，正在扫描文件并写入检索片段。");
    },
    onSuccess: (data, sourceId) => {
      setIndexResult(data);
      setLastIndexedAt(new Date().toISOString());
      setAskForm((current) => (current.sourceId ? current : { ...current, sourceId: String(sourceId) }));
      void queryClient.invalidateQueries({ queryKey: ["knowledge-sources"] });
      onNotice(`索引完成：${data.documentsIndexed} 个文档，${data.chunksIndexed} 个片段。`);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "索引失败。"),
    onSettled: () => setIndexingSourceId(null),
  });

  const search = useMutation({
    mutationFn: () =>
      api.searchKnowledge({
        query: askForm.query,
        sourceId: asNumberOrNull(askForm.sourceId),
        topK: asNumberOrNull(askForm.topK) ?? 5,
      }),
    onSuccess: (data) => {
      setSearchResult(data);
      setAnswerResult(null);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "知识库检索失败。"),
  });

  const ask = useMutation({
    mutationFn: () =>
      api.askKnowledge({
        query: askForm.query,
        sourceId: asNumberOrNull(askForm.sourceId),
        topK: asNumberOrNull(askForm.topK) ?? 5,
      }),
    onSuccess: (data) => {
      setAnswerResult(data);
      setSearchResult(null);
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "知识库问答失败。"),
  });

  return (
    <div className="mx-auto grid w-full max-w-6xl gap-5">
      <Card className="min-w-0 overflow-hidden">
        <CardHeader>
          <div>
            <CardTitle>知识源</CardTitle>
            <CardDescription>把 Markdown、AI 资产和项目代码证据变成可检索知识。</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              createSource.mutate();
            }}
          >
            <div className="grid grid-cols-2 gap-3 max-lg:grid-cols-1">
              <Label className="min-w-0">
                名称
                <Input value={sourceForm.name} onChange={(event) => setSourceForm({ ...sourceForm, name: event.target.value })} />
              </Label>
              <Label className="min-w-0">
                类型
                <select
                  className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                  value={sourceForm.sourceType}
                  onChange={(event) => setSourceForm({ ...sourceForm, sourceType: event.target.value })}
                >
                  <option value="project_ai_docs">项目 AI 资产 + 代码证据</option>
                  <option value="markdown_dir">Markdown 目录</option>
                </select>
              </Label>
            </div>
            <Label className="min-w-0">
              根路径
              <Input value={sourceForm.rootPath} onChange={(event) => setSourceForm({ ...sourceForm, rootPath: event.target.value })} />
            </Label>
            <div className="flex flex-wrap gap-2">
              <Button disabled={createSource.isPending}>
                {createSource.isPending ? <Loader2 className="size-4 animate-spin" /> : <Plus className="size-4" />}
                添加知识源
              </Button>
              <Button type="button" variant="secondary" onClick={() => setSourceForm({ name: "DevContext Docs", rootPath: "D:\\CodeX\\DevContext\\docs", sourceType: "markdown_dir" })}>
                填入 DevContext docs
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <Card className="min-w-0 overflow-hidden">
        <CardHeader>
          <CardTitle>来源列表</CardTitle>
          <CardDescription>{sources.length} 个知识源。</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-2">
          {sources.length === 0 ? (
            <EmptyState text="还没有知识源。" />
          ) : (
            sources.map((source) => (
              <div key={source.id} className="min-w-0 rounded-lg border border-border bg-background p-3">
                <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_auto] md:items-start">
                  <div className="min-w-0">
                    <div className="flex min-w-0 flex-wrap items-center gap-2">
                      <Badge variant="secondary">Source #{source.id}</Badge>
                      <div className="min-w-0 break-words font-medium">{source.name}</div>
                    </div>
                    <div className="mt-1 break-all font-mono text-xs leading-5 text-muted-foreground">{source.rootPath}</div>
                  </div>
                  <Badge variant={source.status === "indexed" ? "success" : "warning"}>{source.status ?? source.sourceType}</Badge>
                </div>
                <KnowledgeIndexStatus
                  source={source}
                  indexing={indexingSourceId === source.id}
                  indexResult={indexResult?.sourceId === source.id ? indexResult : null}
                  indexedAt={indexResult?.sourceId === source.id ? lastIndexedAt : null}
                />
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button size="sm" variant="secondary" disabled={indexSource.isPending} onClick={() => indexSource.mutate(source.id)}>
                    {indexingSourceId === source.id ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
                    {indexingSourceId === source.id ? "索引中" : "索引"}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setAskForm((current) => ({ ...current, sourceId: String(source.id) }))}>选择</Button>
                </div>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <Card className="min-w-0 overflow-hidden">
        <CardHeader>
          <div>
            <CardTitle>知识问答</CardTitle>
            <CardDescription>答案需要带可检查的来源片段。</CardDescription>
          </div>
          {answerResult?.runId ? (
            <Button variant="secondary" onClick={() => onOpenRun(answerResult.runId)}>
              <Activity className="size-4" />
              查看追踪
            </Button>
          ) : null}
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="grid grid-cols-[minmax(0,1fr)_120px] gap-3 max-md:grid-cols-1">
            <Label className="min-w-0">
              知识源
              <select
                className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={askForm.sourceId}
                onChange={(event) => setAskForm({ ...askForm, sourceId: event.target.value })}
              >
                <option value="">全部来源</option>
                {sources.map((source) => (
                  <option key={source.id} value={source.id}>{source.name}</option>
                ))}
              </select>
            </Label>
            <Label className="min-w-0">TopK<Input value={askForm.topK} onChange={(event) => setAskForm({ ...askForm, topK: event.target.value })} /></Label>
          </div>
          <Label className="min-w-0">
            问题
            <Textarea className="min-h-28" value={askForm.query} onChange={(event) => setAskForm({ ...askForm, query: event.target.value })} />
          </Label>
          <div className="flex flex-wrap gap-2">
            <Button onClick={() => ask.mutate()} disabled={ask.isPending}>
              {ask.isPending ? <Loader2 className="size-4 animate-spin" /> : <Sparkles className="size-4" />}
              生成答案
            </Button>
            <Button variant="secondary" onClick={() => search.mutate()} disabled={search.isPending}>
              {search.isPending ? <Loader2 className="size-4 animate-spin" /> : <Search className="size-4" />}
              只检索
            </Button>
          </div>
        </CardContent>
      </Card>

      <KnowledgeResult answer={answerResult} search={searchResult} indexResult={indexResult} indexedAt={lastIndexedAt} />
    </div>
  );
}

function MetricTile({ label, value, tone }: { label: string; value: ReactNode; tone?: "success" | "warning" | "danger" }) {
  const toneClass =
    tone === "success"
      ? "text-emerald-200"
      : tone === "warning"
        ? "text-amber-200"
        : tone === "danger"
          ? "text-red-200"
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
        <div key={warning} className="flex min-w-0 items-start gap-2 rounded-md border border-amber-400/20 bg-amber-400/10 p-2 text-xs leading-5 text-amber-100">
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
    <div className="mt-3 rounded-lg border border-emerald-400/20 bg-emerald-400/10 p-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <CheckCircle2 className="size-4 text-emerald-200" />
        <span className="font-medium text-emerald-100">索引完成</span>
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
  search,
  indexResult,
  indexedAt,
}: {
  answer: RagAnswerResult | null;
  search: KnowledgeSearchResponse | null;
  indexResult: KnowledgeIndexResult | null;
  indexedAt: string | null;
}) {
  const queryPlan = answer?.queryPlan ?? search?.queryPlan ?? null;
  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader>
        <CardTitle>结果</CardTitle>
        <CardDescription>{answer ? `Retrieval #${answer.retrievalRecordId}` : search ? `Retrieval #${search.retrievalRecordId}` : indexResult ? `Source #${indexResult.sourceId}` : "答案和引用会显示在这里。"}</CardDescription>
      </CardHeader>
      <CardContent>
        {answer ? (
          <div className="grid gap-4">
            <QueryPlanPanel plan={queryPlan} />
            <pre className="max-w-full overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-background p-4 text-sm leading-6">{answer.answer}</pre>
            <CitationList citations={answer.citations} />
          </div>
        ) : search ? (
          <div className="grid gap-4">
            <QueryPlanPanel plan={queryPlan} />
            <CitationList citations={search.results} />
          </div>
        ) : indexResult ? (
          <KnowledgeIndexResultPanel result={indexResult} indexedAt={indexedAt} />
        ) : (
          <EmptyState text="还没有知识库结果。" />
        )}
      </CardContent>
    </Card>
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
          <CheckCircle2 className="size-4 text-emerald-200" />
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
    return <EmptyState text="没有召回引用片段。" />;
  }
  return (
    <div className="grid gap-3">
      {citations.map((citation) => (
        <article key={citation.chunkId} className="min-w-0 rounded-lg border border-border bg-background p-4">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <Badge variant="success">score {round(citation.fusedScore)}</Badge>
            {(citation.evidenceTypes ?? []).slice(0, 4).map((type) => (
              <Badge key={type} variant="secondary">{evidenceTypeLabel(type)}</Badge>
            ))}
            <span className="min-w-0 break-words font-medium">{citation.title}</span>
            <span className="min-w-0 break-all font-mono text-xs text-muted-foreground">{citation.filePath}</span>
          </div>
          <div className="mt-2 break-words text-xs text-muted-foreground">{citation.headingPath}</div>
          <p className="mt-3 line-clamp-4 break-words text-sm leading-6 text-muted-foreground">{citation.content}</p>
        </article>
      ))}
    </div>
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
  const [geminiApiKey, setGeminiApiKey] = useState("");
  const [deepseekApiKey, setDeepseekApiKey] = useState("");

  useEffect(() => {
    const next = settings?.pending ?? settings;
    if (!next) return;
    setProvider(next.provider);
    setModel(next.model);
  }, [settings?.provider, settings?.model, settings?.pending?.provider, settings?.pending?.model]);

  const selectedProvider = providers.find((item) => item.provider === provider) ?? providers[0];

  function selectProvider(nextProvider: string) {
    setProvider(nextProvider);
    const nextStatus = providers.find((item) => item.provider === nextProvider);
    setModel(nextStatus?.model ?? "mock-llm");
  }

  const saveSettings = useMutation({
    mutationFn: async () => {
      const body: {
        provider: string;
        model: string;
        geminiApiKey?: string | null;
        deepseekApiKey?: string | null;
      } = {
        provider,
        model: model.trim(),
      };
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
      void queryClient.invalidateQueries({ queryKey: ["llm-settings"] });
      void queryClient.invalidateQueries({ queryKey: ["health"] });
      onNotice(result.restartRequired
        ? "LLM 设置已保存。restartRequired=true，请重启 backend 生效。"
        : "LLM 设置已保存，当前配置已一致。");
    },
    onError: (error) => onNotice(error instanceof Error ? error.message : "LLM 设置保存失败。"),
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
                <div className="grid gap-3 md:grid-cols-3">
                  <MetricTile label="当前 provider" value={settings?.provider ?? "-"} tone={settings?.status === "ready" ? "success" : "warning"} />
                  <MetricTile label="当前 model" value={settings?.model ?? "-"} />
                  <MetricTile label="key 状态" value={settings?.keyStatus ?? "-"} tone={settings?.keyConfigured ? "success" : "warning"} />
                </div>

                <div className="grid gap-4 rounded-lg border border-border bg-background p-4">
                  <div className="grid gap-3 md:grid-cols-[180px_minmax(0,1fr)]">
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
                      <Input className="mt-1" value={model} onChange={(event) => setModel(event.target.value)} placeholder={selectedProvider?.model ?? "model"} />
                    </Label>
                  </div>

                  <div className="grid gap-3 md:grid-cols-2">
                    <Label>
                      Gemini API key
                      <Input
                        className="mt-1"
                        type="password"
                        value={geminiApiKey}
                        onChange={(event) => setGeminiApiKey(event.target.value)}
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
                        onChange={(event) => setDeepseekApiKey(event.target.value)}
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
                    </div>
                    <Button onClick={() => saveSettings.mutate()} disabled={saveSettings.isPending || !model.trim()}>
                      {saveSettings.isPending ? <Loader2 className="size-4 animate-spin" /> : <Settings2 className="size-4" />}
                      保存设置
                    </Button>
                  </div>
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
                <CheckCircle2 className="size-4 text-emerald-200" />
                当前生效
              </div>
              <div className="mt-3 grid gap-2 text-sm text-muted-foreground">
                <StateLine label="provider" value={settings?.provider ?? "-"} />
                <StateLine label="model" value={settings?.model ?? "-"} />
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
                  <AlertTriangle className="size-4 text-amber-200" />
                ) : (
                  <ShieldCheck className="size-4 text-sky-200" />
                )}
                本地 pending
              </div>
              {settings?.pending ? (
                <div className="mt-3 grid gap-2 text-sm text-muted-foreground">
                  <StateLine label="provider" value={settings.pending.provider} />
                  <StateLine label="model" value={settings.pending.model} />
                  <StateLine label="key" value={settings.pending.keyStatus} />
                  <StateLine label="config" value={settings.pending.localConfigPath} />
                </div>
              ) : (
                <p className="mt-3 text-sm text-muted-foreground">没有待重启生效的本地 LLM 配置。</p>
              )}
              {settings?.restartRequired ? (
                <div className="mt-3 rounded-md border border-amber-400/25 bg-background px-3 py-2 text-sm text-amber-100">
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

function defaultLlmProviders(): LlmProviderStatus[] {
  return [
    { provider: "mock", model: "mock-llm", status: "ready", keyRequired: false, keyConfigured: false, keyStatus: "not_required" },
    { provider: "gemini", model: "gemini-2.0-flash", status: "missing_key", keyRequired: true, keyConfigured: false, keyStatus: "missing" },
    { provider: "deepseek", model: "deepseek-chat", status: "missing_key", keyRequired: true, keyConfigured: false, keyStatus: "missing" },
  ];
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
                    {event.errorMessage ? <div className="mt-2 text-sm text-red-200">{event.errorMessage}</div> : null}
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
      {run.errorMessage ? <p className="mt-2 line-clamp-2 text-xs text-red-200">{run.errorMessage}</p> : null}
    </button>
  );
}

function ImportProjectDialog({
  onSelectProject,
  onNotice,
}: {
  onSelectProject: (projectId: string) => void;
  onNotice: (message: string) => void;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "life-service", rootPath: lifeServicePath, defaultBranch: "main" });

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
          导入项目
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>导入本地项目</DialogTitle>
          <DialogDescription>保存项目路径和默认分支。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <Label>名称<Input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></Label>
          <Label>本地路径<Input value={form.rootPath} onChange={(event) => setForm({ ...form, rootPath: event.target.value })} /></Label>
          <Label>默认分支<Input value={form.defaultBranch} onChange={(event) => setForm({ ...form, defaultBranch: event.target.value })} /></Label>
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" variant="secondary" onClick={() => setForm({ name: "life-service", rootPath: lifeServicePath, defaultBranch: "main" })}>life-service</Button>
            <Button type="button" size="sm" variant="secondary" onClick={() => setForm({ name: "DevContext", rootPath: devContextPath, defaultBranch: "main" })}>DevContext</Button>
          </div>
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="ghost">取消</Button></DialogClose>
          <Button onClick={() => create.mutate()} disabled={create.isPending}>保存</Button>
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
      <div className={cn("mt-2 text-2xl font-semibold", tone === "good" && "text-emerald-200", tone === "warn" && "text-amber-200", tone === "bad" && "text-red-200")}>{value}</div>
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

function qualityTone(level?: string): "good" | "warn" | "bad" | "neutral" {
  if (level === "high") return "good";
  if (level === "medium") return "warn";
  if (level === "low") return "bad";
  return "neutral";
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
      label: "可直接使用",
      description: "自动上下文资产较完整，知识库问答和代码审查可以优先信任这些项目事实。",
      badge: "success",
      icon: CheckCircle2,
      iconClass: "border-emerald-400/25 bg-emerald-400/10 text-emerald-200",
    };
  }
  if (level === "medium") {
    return {
      label: "需要抽查",
      description: "上下文可用，但仍有缺失或 TODO。适合辅助问答，关键结论需要打开来源文件确认。",
      badge: "warning",
      icon: ShieldCheck,
      iconClass: "border-amber-400/25 bg-amber-400/10 text-amber-200",
    };
  }
  return {
    label: "不建议直接依赖",
    description: "项目事实还没有被确认，AI 的回答可能只是基于代码结构的推断。请先处理下方待确认项。",
    badge: "danger",
    icon: AlertTriangle,
    iconClass: "border-red-400/25 bg-red-400/10 text-red-200",
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

export default App;
