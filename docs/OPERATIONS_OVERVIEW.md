# 運用概要 (Operations Overview)

このドキュメントは、Jenkins の運用系パイプラインに関する責務分担と実行フローを簡潔にまとめたものです。

## 対象パイプライン

- `src/update-machosts.groovy`
  - Debian/Ubuntu 系 machost 群の定期更新
  - ホストごとの例外設定を最小スコープで適用
- `src/update-proxmox-hosts.groovy`
  - Proxmox ホストの更新候補監視
  - `APPLY_UPDATES=true` 時のみ更新実行
  - クラスタ時は quorum を確認してから更新
- `src/declarative-pipeline.groovy`
  - Jenkins 実行ノードのグローバル IP をもとに Cloudflare allowlist を更新
- `src/monitor-email-alerts.groovy`
  - IMAP メールボックスを定期監視
  - 件名 / 宛先 / 送信元 / 本文キーワードでメールを検知
  - 一致メールを Slack へ通知
  - `UID` を state JSON に保存して重複通知を防止
- `src/update-qiita-engagement.groovy`
  - Qiita の Organization 投稿を定期監視
  - 新着記事へ自動で「いいね」「ストック」を付与
  - 処理済み記事IDを state ファイルへ保存して重複処理を防止
- `src/network-speedtest.groovy`
  - 外部 (インターネット) 向けの帯域を定期計測
  - 結果を 1 つの JSON にまとめて Jenkins コンソール出力 + アーティファクト保存
  - VIP / Tunnel 等の内部経路は Pod から到達できない可能性が高いため対象外
- `src/github-copilot-pr-review.groovy`
  - GitHub `pull_request` Webhook を受け取り PR を GitHub Copilot CLI で自動レビュー
  - `@github/copilot` npm パッケージを使って差分を AI 解析
  - レビュー結果を PR の通常コメントとして自動投稿
  - `$.repository.full_name` でリポジトリを動的判定（複数リポジトリ共有可）

## 実行基盤

- `src/update-machosts.groovy` / `src/update-proxmox-hosts.groovy` / `src/declarative-pipeline.groovy`
  - Jenkins agent: `machost`
  - 実行方式: SSH ベースのリモート実行
- `src/monitor-email-alerts.groovy`
  - Jenkins agent: `kubernetes`
  - 実行方式: Kubernetes Pod ベースのコンテナ実行
  - state を永続化したい場合は `PVC_CLAIM_NAME` で永続ボリュームをマウントする
- `src/update-qiita-engagement.groovy`
  - Jenkins agent: `kubernetes`
  - 実行方式: Kubernetes Pod ベースのコンテナ実行
  - `STATE_FILE_PATH` を PVC 配下へ向けると重複処理を防止しやすい
- `src/network-speedtest.groovy`
  - Jenkins agent: `kubernetes`
  - 実行方式: Kubernetes Pod ベースのコンテナ実行（既定イメージ `nicolaka/netshoot:latest`）
  - `speedtest-cli` を優先利用し、未導入時は `EXTERNAL_FALLBACK_URL` を `curl` でダウンロードして帯域換算
- `src/github-copilot-pr-review.groovy`
  - Jenkins agent: `kubernetes`
  - 実行方式: Kubernetes Pod ベースのコンテナ実行（イメージ `honoka4869/jenkins-maven-node:latest`）
  - トリガー: Generic Webhook Trigger による GitHub `pull_request` イベント
- スケジュール: 各 pipeline の `cron` 設定に従う

## 通知と認証情報

### Proxmox 通知

- `slack-webhook-url` (Secret text)
- `discord-webhook-url` (Secret text)
- `TEST_NOTIFICATIONS_ONLY=true` で通知疎通のみ確認可能

### Cloudflare 更新

- `CF_API_TOKEN` (Secret text)
- `CF_ZONE_ID` (Secret text)

### メール監視通知

- `mail-monitor-imap` (Username with password)
  - IMAP ログイン用
- `slack-bot-token` (Secret text)
  - Slack API `chat.postMessage` 用の Bot/User Token
- `SLACK_CHANNEL` (Jenkins パラメータ)
  - Slack の通知先チャンネル名またはチャンネル ID
- `STATE_FILE_PATH` (Jenkins パラメータ)
  - 監視済み `UID` の保存先
  - `kubernetes` コンテナ Agent 運用時は PVC 配下を推奨
  - 例: `/mail-monitor-state/alerts-inbox.json`

### Qiita 自動エンゲージメント

- `jqit-qiita-access-token` (Secret text)
  - 必須スコープ: `read_qiita`, `write_qiita`
  - 通常 Qiita の記事を対象にする場合に利用
- `STATE_FILE_PATH` (Jenkins パラメータ)
  - 処理済み記事IDの保存先を指定
  - コンテナAgent運用時は永続ボリューム（PVC など）のマウント先を指定する
  - 例: `/data/qiita-auto-engagement/processed_item_ids.txt`

### GitHub Copilot PR レビュー

認証情報は**役割を分離した 2 トークン構成を推奨**する。同一トークンでの兼用は例外運用とし、漏洩時の影響範囲を限定する。

- `jqit-github-token` (Secret text) — **読み取り + Copilot CLI 認証専用**
  - GitHub Copilot CLI 認証および PR diff 取得用の Personal Access Token
  - Fine-grained PAT または GitHub App により最小権限（Repository permissions の `Pull requests: Read` / `Contents: Read` / `Copilot Requests`）に絞る
  - **書き込み権限は付与しない**。Copilot CLI のサプライチェーン経由で意図せず書き込み API が叩かれた場合の影響を遮断する
  - GitHub Copilot サブスクリプション（Individual / Business / Enterprise）が必要
- `jqit-github-token-classic` (Secret text) — **コメント書き込み専用**
  - PR への通常コメント投稿（GitHub Issues API）専用の Personal Access Token
  - Classic PAT は最小スコープ `public_repo`（プライベート対象を含む場合のみ `repo`）。Fine-grained PAT であれば `Pull requests: Write`（または `Issues: Write`）のみを付与する
  - 読み取り側のトークンと別 ID で管理し、ローテーションも独立して行えるようにする
- 例外: 同一トークンへの兼用
  - 運用都合でやむを得ず兼用する場合は、リスク（書き込み権限を持つトークンが Copilot CLI のプロセスに渡る）を関係者で合意したうえで、対象リポジトリを限定し、ローテーション周期を短くする
- Webhook トークン
  - Generic Webhook Trigger の `token` パラメータには、推測困難な十分長いランダム文字列を Jenkins Credential 等で管理して使用する
  - リポジトリにサンプルとして掲載しているトークン名（例: `github-copilot-pr-review`）はそのまま運用値として使用しない
- Webhook ペイロードの取り扱い（共有エンドポイント運用時の必須要件）
  - 本パイプラインは `$.repository.full_name` をそのまま信頼してリポジトリを動的判定するため、共有エンドポイント運用では以下を**必須**とする
    - Jenkins ジョブ側で許可するリポジトリの allowlist（許可する `owner/repo` を環境変数や `repositoryConfig` で定義し、合致しない場合は早期 `error` で停止）
    - GitHub Webhook secret の設定と `X-Hub-Signature-256` の HMAC 検証（Generic Webhook Trigger 自体は署名検証を行わないため、リバースプロキシ層または専用プラグインで検証する／IP 制限・送信元制限と併用する）
  - これらを設けないまま共有エンドポイントを公開すると、想定外のリポジトリから同一ジョブが起動され、Jenkins 側のトークンで外部 PR にコメントする踏み台になり得る
- レビューコメントの重複抑止方針
  - 現状は `opened` / `synchronize` / `reopened` のたびに**毎回新規コメントを追記**する実装で、既存 bot コメントの編集や古いコメントの自動整理は行わない
  - PR がノイジーになりやすいため、運用ルールとして以下のいずれかを選択することを推奨する
    - 追記を許容し、最新コメントのみ参照する運用とする
    - 必要に応じてパイプラインを拡張し、過去 bot コメント（自身のユーザー名で投稿されたもの）を `PATCH /repos/{owner}/{repo}/issues/comments/{comment_id}` で更新、または `DELETE` で整理する方式に切り替える
  - 拡張する場合は、書き込み専用トークンに `Pull requests: Write` 相当のみが付与されていることを再確認する

## 運用上の設計原則

- 監視と更新を分離し、デフォルトでは破壊的変更を行わない
- 変更は idempotent（差分があるときのみ更新）を優先
- Jenkins の sandbox / CPS 制約を考慮し、Groovy と shell は単純化する
- 失敗はホスト単位で可視化し、全体の失敗原因を追跡しやすくする

## SKILL との対応

- `jenkins-machost-updates` → `src/update-machosts.groovy`
- `proxmox-host-maintenance` → `src/update-proxmox-hosts.groovy`
- `cloudflare-allowlist-pipeline` → `src/declarative-pipeline.groovy`
- `macos-jenkins-agent-launchd` → `docs/templates/local.Jenkins.Agent.launchd.plist`
- `github-copilot-pr-review` → `src/github-copilot-pr-review.groovy`
