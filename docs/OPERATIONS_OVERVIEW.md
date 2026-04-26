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

- `jqit-github-token` (Secret text)
  - GitHub Copilot CLI 認証用の Personal Access Token
  - 用途は PR 差分取得とコメント投稿、および Copilot CLI の認証のみのため、`repo` のような広いスコープは付与せず、Fine-grained PAT または GitHub App により最小権限（例: Repository permissions の `Pull requests: Read` / `Contents: Read` / `Copilot Requests`）に絞ることを推奨する
  - もし PR へのコメント投稿を同じトークンで行う場合は、追加で `Pull requests: Write`（または `Issues: Write`）を付与する。書き込みを別 Credential （例: Classic PAT）に分離する構成も采り得る
  - ※本ドキュメントでは Read 権限のみを推奨として記載しているものの、セットアップの際はコメント投稿トークンの位置づけを忘れずに検討すること
  - GitHub Copilot サブスクリプション（Individual / Business / Enterprise）が必要
- Webhook トークン
  - Generic Webhook Trigger の `token` パラメータには、推測困難な十分長いランダム文字列を Jenkins Credential 等で管理して使用する
  - リポジトリにサンプルとして掲載しているトークン名（例: `github-copilot-pr-review`）はそのまま運用値として使用しない
- Webhook ペイロードの取り扱い
  - 本パイプラインは `$.repository.full_name` をそのまま信頼してリポジトリを動的判定するため、Webhook 経路自体の防御策を併用する
    - Jenkins ジョブ側で許可するリポジトリの allowlist（例: 許可する `owner/repo` を環境変数や `repositoryConfig` で定義し、合致しない場合は早期 `error` で停止）を設けることを推奨する
    - Generic Webhook Trigger 側で受信できる場合は GitHub Webhook の署名検証（`X-Hub-Signature-256` を Webhook secret で HMAC 検証）を有効化し、未署名リクエストを拒否する
  - これらを設けない運用では、トークンの権限次第で意図しないリポジトリへのレビュー/コメント実行に繋がる恐れがあるため、最小権限と allowlist / 署名検証を併用する

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
