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
- `src/update-qiita-engagement.groovy`
  - Qiita の Organization 投稿を定期監視
  - 新着記事へ自動で「いいね」「ストック」を付与
  - 処理済み記事IDを state ファイルへ保存して重複処理を防止

## 実行基盤

- Jenkins agent: `machost`
- 実行方式: SSH ベースのリモート実行
- スケジュール: 各 pipeline の `cron` 設定に従う

## 通知と認証情報

### Proxmox 通知

- `slack-webhook-url` (Secret text)
- `discord-webhook-url` (Secret text)
- `TEST_NOTIFICATIONS_ONLY=true` で通知疎通のみ確認可能

### Cloudflare 更新

- `CF_API_TOKEN` (Secret text)
- `CF_ZONE_ID` (Secret text)

### Qiita 自動エンゲージメント

- `qiita-access-token` (Secret text)
  - 必須スコープ: `read_qiita`, `write_qiita`
  - 通常 Qiita の記事を対象にする場合に利用

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
