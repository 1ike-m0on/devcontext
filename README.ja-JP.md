# DevContext

[中文](./README.md) | [English](./README.en.md) | 日本語

DevContext は、ローカルのコードリポジトリ向け AI 開発ワークスペースです。個人開発者が、プロジェクトファイル、ソースコード上の根拠、ナレッジベース回答、コードレビュー、技術判断、AI 実行トレースを、再利用できる検証可能なコンテキストとして整理することを支援します。

DevContext は単なるチャット UI ではありません。より実際的な問題を扱います。

> AI がコードベースについて回答するとき、どのソースファイルを見るべきか、どの根拠を使ってはいけないか、引用元はどこか、根拠が不足している場合はいつ答えないべきかを明確にする。

## 想定ユーザー

DevContext は次のような開発者に向いています。

- AI にプロジェクトを説明するたびに、同じ構成、パス、設定、過去の判断を繰り返し伝えている。
- ローカルリポジトリを、検索可能で質問できるプロジェクト作業台にしたい。
- AI の回答を一般的な推測ではなく、実際のソースコード上の根拠に基づけたい。
- コミット前に、現在の変更、最新コミット、ブランチ差分を AI にレビューさせたい。
- 技術判断、レビューのフィードバック、AI の実行履歴をあとで再利用したい。

現在の DevContext は個人向けのローカルツールです。企業向けの権限管理、監査基盤、自動コード修正ロボットではありません。

## 主な機能

### 1. プロジェクト登録とナレッジ索引

ローカルプロジェクトを作成または選択すると、DevContext はそのプロジェクト用のナレッジ索引を作成します。索引は、コード、設定、SQL、テスト、ドキュメント、生成された `.ai` アセットを読み取り、後続の質問でプロジェクト事実を検索できるようにします。

索引はプロジェクト単位の処理です。プロジェクト内容が変わった場合は、索引を更新してください。

### 2. Source-Grounded Knowledge RAG

ユーザーが質問すると、DevContext はその質問に必要なコンテキストを自動的に準備します。

1. 質問の意図を理解する。
2. 回答に必要な根拠が、ソースコード、テスト、設定、SQL、デプロイ、実行記録、または別の種類かを判断する。
3. プロジェクト構造とナレッジ索引から primary evidence を選ぶ。
4. 生成ドキュメント、無関係な文書、ノイズになるテスト、使ってはいけない source を除外する。
5. 選択された根拠に基づいて回答を生成する。
6. Markdown 回答、source paths、citations、展開可能な trace を返す。

質問前に別途「コンテキスト生成」をクリックする必要はありません。コンテキスト生成は各質問の処理中に行われます。

### 3. AI コードレビュー

DevContext は次の入力からレビューを作成できます。

- 現在の作業ツリーの変更。
- 最新コミット。
- 現在ブランチとデフォルトブランチの差分。
- 手動で貼り付けた diff。

レビュー結果は、重要度、ファイルパス、説明、影響、修正案、信頼度を持つ構造化 Issue として保存されます。各 Issue は accepted、false positive、fixed、rejected などに更新でき、フィードバックは後続の確認や品質改善に利用できます。

### 4. 技術判断メモリ

キャッシュ戦略、冪等性設計、ページング、メッセージ補償などの重要な技術判断を Decision Card として保存できます。カードには、状況、選択肢、最終判断、理由、トレードオフ、適用条件、根拠を記録できます。

似た問題が後で出たとき、DevContext は関連する判断を検索し、再利用できる部分と調整が必要な部分を見分ける助けになります。

### 5. トレースと説明可能性

DevContext は AgentRun、AgentEvent、RetrievalRecord、evidence evaluation を記録します。どの根拠が使われたか、どの source path が検索されたか、どのモデルが呼ばれたか、no-answer guard が発動したかを確認できます。

## 推奨ワークフロー

1. DevContext のフロントエンドを開く。
2. プロジェクトを作成または選択する。
3. プロジェクトパスを選ぶ。
4. ナレッジ索引を作成または更新する。
5. Knowledge Q&A で質問する。
6. Markdown 回答、source paths、citations を読む。
7. 必要に応じて trace / evidence details を展開する。

コンテキスト生成は 5 番目の質問処理の中で行われます。

## クイックスタート

ローカル環境全体を起動するには Docker Compose が簡単です。

```bash
docker compose up -d --build
```

起動後、次の URL を開きます。

- Frontend: http://localhost:5173
- Backend API: http://localhost:18080
- Qdrant: http://localhost:6333

Docker モードでは、バックエンドはコンテナ内で動作するため、コンテナにマウントされたプロジェクトディレクトリだけを参照できます。デフォルトのマウントは次の通りです。

```text
./workspace/projects -> /workspace/projects
```

最初に試す場合は、分析したいプロジェクトを `workspace/projects/` 配下に置き、DevContext では次のようなコンテナ内パスを選びます。

```text
/workspace/projects/my-project
```

別のホストディレクトリを使う場合:

```powershell
$env:DEVCONTEXT_PROJECTS_DIR="C:\Users\you\Documents\projects"
docker compose up -d --build
```

その後、DevContext では次の形式で選びます。

```text
/workspace/projects/your-project
```

## モデル設定

デフォルトの Docker 構成では `mock` モデルを使います。プロジェクト登録、索引、フロントエンドの流れ、基本機能を確認するのに向いています。

実モデルを使う場合は `.env.example` をコピーします。

```powershell
Copy-Item .env.example .env
```

その後、provider と API Key を設定します。

DeepSeek の例:

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_TIMEOUT=120s
```

Gemini の例:

```properties
DEVCONTEXT_LLM_PROVIDER=gemini
GEMINI_API_KEY=<your-api-key>
GEMINI_MODEL=gemini-2.0-flash
```

`.env` を変更した後は、通常バックエンドの再起動だけで十分です。

```bash
docker compose up -d backend
```

`Dockerfile`、`pom.xml`、フロントエンド依存関係を変更した場合は再ビルドします。

```bash
docker compose up -d --build
```

API Key、ローカル DB、実行ログ、生成された private context は Git にコミットされません。

## Vector Store

Docker Compose ではデフォルトで Qdrant を使います。

```properties
DEVCONTEXT_VECTOR_PROVIDER=qdrant
QDRANT_BASE_URL=http://qdrant:6333
```

ローカル開発では JDBC vector store も利用できます。

```properties
DEVCONTEXT_VECTOR_PROVIDER=jdbc
```

現在のバージョンでは、独自の embedding / vector adapter も保持しています。Spring AI による embedding、vector store、tool calling、observability の統合は今後のインフラ改善です。

## よく使うコマンド

バックエンドテスト:

```bash
mvn test
```

フロントエンドビルド:

```bash
npm.cmd run frontend:build
```

コンテキスト品質ゲート:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-benchmark.ps1 -Suite all
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-benchmark.ps1 -Suite evidence-pack
```

Knowledge RAG 受け入れ:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1
```

Code Review 受け入れ:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-code-review-benchmark.ps1 -TimeoutSeconds 150 -RetryCount 1
```

## 現在の受け入れ状態

現在のバージョンでは、ローカル利用に必要な主要ループが動作します。

- プロジェクトの作成、編集、削除、パス選択。
- 状態表示つきのナレッジ索引。
- source-grounded Knowledge RAG 回答。
- Markdown 回答、source paths、citations、trace details の表示。
- AI Code Review とレビュー feedback。
- Decision Card と基本的な decision recall。
- AgentRun / RetrievalRecord trace。
- 主要ワークフローを扱う React フロントエンド。

コンテキストと Knowledge RAG の品質は、no-LLM context benchmark、evidence-pack、HTTP benchmark、real LLM smoke/sample によって検証されています。最新の merged baseline では、core context gates と Knowledge RAG acceptance が通過しています。

## 境界と制限

DevContext は次のものではありません。

- 自律的にコードを編集する Agent。
- Git commit や PR 作成を自動で行うツール。
- IDE レベルの完全な意味解析器。
- 完全な ReAct Agent フレームワーク。
- 企業向けの権限管理、監査、多ユーザー協作基盤。
- どんな質問にも必ず答えるチャットボット。

根拠が不足している場合、DevContext は無理に推測せず、証拠不足を示すべきです。目的は次の一点です。

> 個人開発者が、プロジェクト事実、ソースコード上の根拠、ローカル知識、コードレビュー結果、技術判断を、AI が継続的に使えるローカルコンテキスト作業台として整理できるようにすること。
