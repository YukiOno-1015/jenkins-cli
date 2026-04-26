# GitHub Copilot PR レビューパイプライン

[`src/github-copilot-pr-review.groovy`](../src/github-copilot-pr-review.groovy) は、GitHub の `pull_request` Webhook を受け取り、GitHub Copilot CLI (`@github/copilot`) で PR の差分をレビューし、その結果を PR の通常コメントとして投稿する Jenkins Declarative Pipeline です。

## 概要

- トリガー: GitHub `pull_request` Webhook（`opened` / `synchronize` / `reopened` のみ）
- 実行環境: Kubernetes Pod（`honoka4869/jenkins-maven-node:latest`、npm 内蔵）
- レビュー対象: GitHub API から取得した PR の diff（先頭 10,000 バイトまで）
- 出力先: 対象 PR の Issue コメント

`$.repository.full_name` でリポジトリを動的に判定するため、複数リポジトリで同一 Webhook エンドポイントを共有できます。

## 必要な Jenkins Credentials

認証情報は**役割を分離した 2 トークン構成を推奨**する（最小権限の原則）。同一トークンでの兼用は例外運用とする。

| ID | 種別 | 用途 | 推奨スコープ／権限 |
| --- | --- | --- | --- |
| `jqit-github-token` | Secret text | Copilot CLI 認証および PR diff 読み取り（`COPILOT_GITHUB_TOKEN` / `GH_TOKEN` に展開） | Fine-grained PAT: `Pull requests: Read` / `Contents: Read` / `Copilot Requests`。**書き込み権限は付与しない**。Copilot サブスクリプション必須 |
| `jqit-github-token-classic` | Secret text | PR への通常コメント投稿（GitHub Issues API、`GITHUB_TOKEN` に展開） | Classic PAT: `public_repo`（プライベート対象を含む場合のみ `repo`） / Fine-grained: `Pull requests: Write` または `Issues: Write` のみ |

`copilot` CLI は `COPILOT_GITHUB_TOKEN` を優先して認証に利用するため、読み取り用トークンを `COPILOT_GITHUB_TOKEN` および `GH_TOKEN` に、書き込み用トークンを `GITHUB_TOKEN` にエクスポートして使い分ける構成を推奨する。なお Fine-grained PAT に Issues / PR 書き込み権限を組み合わせた場合に 403 が発生する事象が確認されているため、書き込み API には Classic PAT（`jqit-github-token-classic`）を採用している。

例外運用として 1 トークンへ集約する場合は、書き込み権限が Copilot CLI プロセスに渡る点をリスクとして合意したうえで、対象リポジトリを限定し短いローテーション周期で運用する。

## 必要な Jenkins プラグイン

- Generic Webhook Trigger Plugin
- Kubernetes Plugin

## GitHub Webhook 設定

レビュー対象の各リポジトリに以下を設定します。

- Payload URL: `https://<jenkins-host>/generic-webhook-trigger/invoke?token=github-copilot-pr-review`
- Content type: `application/json`
- Events: `Pull requests`

## 処理フロー

1. Webhook ペイロードから PR 番号・リポジトリ名・ブランチ・SHA 等を抽出する。
2. `npm install -g @github/copilot` で Copilot CLI を導入する。
3. GitHub API から `Accept: application/vnd.github.v3.diff` で PR の差分を取得する。
4. `copilot -p "<プロンプト>"` で非インタラクティブにレビュー結果を生成する。
5. GitHub Issues API (`POST /repos/{owner}/{repo}/issues/{issue_number}/comments`) でレビューコメントを投稿する。

## 注意事項

- `copilot -p` の引数長と Copilot 側のコンテキスト長を考慮し、差分は先頭 10,000 バイトに切り詰めています。差分が大きい PR ではコメント末尾にその旨を明記します。
- レビュー生成に失敗した場合は手動レビューを促すフォールバックコメントを投稿します。
- 投稿コメントは `node -e` で JSON を組み立ててから `curl -d @file` で送信しており、改行や引用符を含む本文でも安全に投稿できます。

### セキュリティ要件（共有エンドポイント運用時の必須事項）

本パイプラインは Webhook ペイロードの `$.repository.full_name` を信頼してリポジトリを動的判定するため、共有エンドポイント運用では以下を**必須**とする。これらを設けない場合、想定外のリポジトリから同一ジョブを起動され、Jenkins 側のトークンで外部 PR にコメントする踏み台になり得る。

- 許可リポジトリの allowlist
  - Jenkins ジョブ側で許可する `owner/repo` の一覧を環境変数または `repositoryConfig` で定義する
  - Webhook ペイロードの `repository.full_name` が allowlist に含まれない場合は、即座に `error` で停止する
- Webhook secret と署名検証
  - GitHub Webhook secret を設定し、`X-Hub-Signature-256` の HMAC 検証を必須化する
  - Generic Webhook Trigger 自体は署名検証を行わないため、リバースプロキシ層または専用プラグインで検証する。IP 制限や送信元制限と併用する
- Webhook トークン
  - Generic Webhook Trigger の `token` には推測困難なランダム値を Jenkins Credential で管理して使用し、サンプル名のまま運用しない

### レビューコメントの重複抑止方針

本パイプラインは `opened` / `synchronize` / `reopened` のたびに**毎回新規コメントを追記**する実装で、既存 bot コメントの編集や古いコメントの自動整理は行わない。同一 PR への push が連続するとコメントが積み上がり PR がノイジーになるため、運用ルールとして以下のいずれかを選択することを推奨する。

- **追記許容**: 最新コメントのみを参照する運用とし、過去のレビューコメントは履歴として残す
- **更新方式へ拡張**: パイプラインを拡張し、過去 bot コメント（自身のユーザー名で投稿されたもの）を `PATCH /repos/{owner}/{repo}/issues/comments/{comment_id}` で更新、または `DELETE` で整理する方式に切り替える

更新方式へ拡張する場合は、書き込み専用トークン（`jqit-github-token-classic`）に `Pull requests: Write` 相当のみが付与されていることを再確認する。
