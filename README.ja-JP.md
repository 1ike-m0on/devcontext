# DevContext

[中文](./README.md) | [English](./README.en.md) | 日本語

DevContext は、個人開発者向けのローカル AI 開発アシスタントです。コードベースを取り込むと、AI が読みやすいプロジェクト文脈を生成し、検索可能なローカル知識ベースを作り、Git の変更をレビューし、レビュー結果・技術判断・AI 実行履歴を再利用可能な開発コンテキストとして蓄積します。

DevContext が解決したいのは、単なるチャット UI ではありません。

> AI にプロジェクトを理解させるたびに、同じ背景説明、パス、diff、設計メモを何度も貼り直したくない。

## 想定ユーザー

DevContext は次のような開発者に向いています。

- AI を使って開発しているが、毎回プロジェクト背景を説明している。
- 新しいローカルリポジトリを、AI が理解しやすい形に整理したい。
- コミット前に、現在の変更・最新コミット・ブランチ差分を AI にレビューさせたい。
- 設計メモ、テスト記録、調査メモ、ローカルドキュメントを検索可能にしたい。
- 重要な技術判断を保存し、似た問題であとから再利用したい。

現在の DevContext は個人向けのローカルツールです。チーム権限管理や企業監査向けのプラットフォームではまだありません。

## できること

### 1. プロジェクトを取り込み、AI 向けコンテキストを生成する

ローカルプロジェクトを選択すると、DevContext はコード、設定、ドキュメント、Git 情報をスキャンし、AI が読みやすいコンテキスト資産を生成します。

- `AGENTS.md`
- `.ai/AI_README.md`
- `.ai/code-map.json`
- `.ai/generated/project-structure.md`
- `.ai/generated/tech-architecture.md`
- `.ai/generated/dev-guide.md`
- `.ai/generated/core-flows.md`

これらのファイルは、AI が「このプロジェクトは何か」「どう起動するか」「重要なモジュールはどこか」「どのファイルを見るべきか」を把握するための入口になります。

### 2. ローカル知識ベースに質問する

プロジェクト文書、`.ai` 資産、SQL、設定、テスト記録、監視メモなどを知識ベースに登録できます。その後、次のような質問ができます。

- 「キャッシュはどう実装されている？」
- 「SQL ではどんなインデックスを使っている？」
- 「セール処理はどう監視されている？」
- 「デプロイ設定はどこにある？」
- 「このプロジェクトの中心的な処理フローは？」

DevContext は出典付きの回答と、実際に検索された文脈を表示します。回答が根拠に基づいているかを確認しやすくなります。

### 3. AI Code Review を行う

DevContext は次の入力からコードレビューを作成できます。

- 現在の作業ツリーの変更。
- 最新コミット。
- ブランチ間の差分。
- 手動で貼り付けた diff。

レビュー結果は構造化された Issue として保存されます。

- 重要度
- ファイルパスと行番号
- 問題説明
- 影響
- 修正提案
- 確信度
- フィードバック状態

各 Issue は accepted、false positive、fixed、rejected などに更新できます。フィードバックは後続の確認や品質改善に使えます。

### 4. 技術判断を保存する

ページング、キャッシュ、冪等性、メッセージ補償などの重要な設計判断を Decision Card として保存できます。

- scenario
- options
- decision
- reasons
- trade-offs
- applicable conditions
- non-applicable conditions
- evidence

後で似た問題が出たとき、DevContext は関連する Decision Card を呼び出し、AI に「再利用できる部分」「調整が必要な部分」「残るリスク」を判断させることができます。

### 5. AI 実行履歴を確認する

AI タスクごとに AgentRun と AgentEvent が記録されます。

- どの diff を取得したか。
- どのコンテキストを読み込んだか。
- prompt のサイズ。
- 呼び出したモデル。
- 応答の解析に成功したか。
- どの結果を保存したか。

AI の出力がブラックボックスになりにくく、後から確認できます。

## クイックスタート

まずは Docker Compose で起動するのが一番簡単です。

```bash
docker compose up -d --build
```

起動後、以下にアクセスできます。

- Frontend: http://localhost:5173
- Backend: http://localhost:18080
- Qdrant: http://localhost:6333

Docker モードでは、バックエンドはコンテナ内で動作するため、コンテナにマウントされたプロジェクトディレクトリだけを読み書きできます。デフォルトのマウントは次の通りです。

```text
./workspace/projects -> /workspace/projects
```

最初に試す場合は、分析したいプロジェクトを `workspace/projects/` に置き、DevContext では次のようなコンテナ内パスを指定してください。

```text
/workspace/projects/my-project
```

別のホストディレクトリを使う場合:

```powershell
$env:DEVCONTEXT_PROJECTS_DIR="C:\Users\you\Documents\projects"
docker compose up -d --build
```

その後、DevContext では次の形式で取り込みます。

```text
/workspace/projects/your-project
```

デフォルトの Docker 構成では `mock` モデルを使うため、実モデルを設定する前に、プロジェクト取り込み、コンテキスト生成、知識ベース索引化、UI の流れを確認できます。

### Docker モードで LLM を切り替える

Docker Compose はリポジトリ直下の `.env` を読み込みます。モデルを切り替えるためにローカルでバックエンドを起動する必要はありません。`.env` を編集し、backend コンテナを再起動してください。

DeepSeek に切り替える例:

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
```

その後:

```bash
docker compose up -d backend
```

`Dockerfile`、`pom.xml`、フロントエンド依存関係など、イメージのビルド内容を変更した場合は次を実行してください。

```bash
docker compose up -d --build
```

`.env` のモデル provider、API Key、timeout、vector provider だけを変えた場合、通常は rebuild 不要です。

## 最初の使い方

1. ローカルプロジェクトを取り込む。
2. プロジェクトワークスペースでコンテキスト資産を再生成する。
3. 生成された `.ai` 資産を知識ベースに登録して索引化する。
4. Knowledge Q&A でプロジェクトについて質問する。
5. Code Review で現在の変更または最新コミットをレビューする。

## ヘルスチェック

```http
GET http://localhost:18080/api/health
```

## 実モデルを使う

`.env.example` を `.env` にコピーし、そのファイルを編集する方法を推奨します。

```powershell
Copy-Item .env.example .env
```

Docker Compose はこの設定を読み込みます。モデルを切り替えた後は `docker compose up -d backend` で backend コンテナを再起動してください。

### Gemini

```properties
DEVCONTEXT_LLM_PROVIDER=gemini
GEMINI_API_KEY=<your-api-key>
GEMINI_MODEL=gemini-2.0-flash
```

### DeepSeek

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_TIMEOUT=120s
```

API Key、ローカル DB、実行ログ、生成された private context は Git にコミットしません。

## Vector Store

デフォルトではローカル JDBC vector store を使用します。`.env` で切り替えることもできます。

```properties
DEVCONTEXT_VECTOR_PROVIDER=jdbc
```

Qdrant を使う場合:

```properties
DEVCONTEXT_VECTOR_PROVIDER=qdrant
QDRANT_BASE_URL=http://localhost:6333
```

## よく使うコマンド

バックエンドテスト:

```bash
mvn test
```

フロントエンドビルド:

```bash
npm.cmd run frontend:build
```

Code Review benchmark:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-code-review-benchmark.ps1 -TimeoutSeconds 150 -RetryCount 1
```

Knowledge benchmark:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1
```

Decision recall benchmark:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-recall-quality-benchmark.ps1
```

## 現在の状態

DevContext は現在、ローカル利用の主要な流れを実行できます。

- プロジェクト取り込みとコンテキスト生成。
- ローカル知識検索と出典付き回答。
- AI Code Review。
- Review Issue のフィードバック。
- Decision Card と基本的な意思決定 recall。
- AgentRun trace。
- 主要機能を扱う React frontend。ただしまだ Alpha 段階です。

引き続き改善中の部分:

- フロントエンドの履歴表示と操作体験。
- コンテキストの信頼度評価。
- より細かいコード位置特定とオンデマンド検索。
- 根拠不足時に推測しすぎない no-answer 挙動。
- より大きな実プロジェクト評価。

## まだ期待しないでほしいこと

DevContext は、自動でコードを書き換えたり、Git にコミットしたり、IDE レベルの完全な意味理解を持つとは主張していません。目的は次の一点です。

> 個人開発者のプロジェクト事実、コード変更、ローカル知識、技術判断を、AI が継続的に使えるコンテキストワークスペースとして整理すること。
