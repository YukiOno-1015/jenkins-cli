# GitHub Copilot PR レビューパイプライン

[`src/github-copilot-pr-review.groovy`](../src/github-copilot-pr-review.groovy) は、GitHub の `pull_request` Webhook を受け取り、GitHub Copilot CLI (`@github/copilot`) で PR の差分をレビューし、その結果を PR の通常コメントとして投稿する Jenkins Declarative Pipeline です。

## 概要

- トリガー: GitHub `pull_request` Webhook（`opened` / `synchronize` / `reopened` のみ）
- 実行環境: Kubernetes Pod（`honoka4869/jenkins-maven-node:latest`、npm 内蔵）
- レビュー対象: GitHub API から取得した PR の diff（先頭 10,000 バイトまで）
- 出力先: 対象 PR の Issue コメント

`$.repository.full_name` でリポジトリを動的に判定するため、複数リポジトリで同一 Webhook エンドポイントを共有できます。

## 必要な Jenkins Credentials

| ID | 種別 | 用途 |
| --- | --- | --- |
| `jqit-github-token` | Secret text | GitHub Fine-grained Personal Access Token。**Copilot CLI 認証専用** (`COPILOT_GITHUB_TOKEN` / `GH_TOKEN` に展開)。Copilot サブスクリプションが付与されたユーザーのトークンを設定する。Repository permissions: `Pull requests: Read`, `Contents: Read`, および `Copilot Requests` を有効化する。 |
| `jqit-github-token-classic` | Secret text | GitHub Personal Access Token (Classic, scope=`repo`)。**PR への issue comment 投稿および PR diff 取得用** (`GITHUB_TOKEN`)。Fine-grained PAT では Issues / PR 書き込み権限の組み合わせで 403 が発生したため、書き込み API は Classic PAT に集約している。 |

`copilot` CLI は `COPILOT_GITHUB_TOKEN` を優先して認証に利用します。本パイプラインでは `GITHUB_TOKEN` の値をそのまま `COPILOT_GITHUB_TOKEN` にエクスポートしています。

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
