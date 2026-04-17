# メール監視パイプライン

`src/monitor-email-alerts.groovy` は、Jenkins の Kubernetes コンテナ Agent 上で IMAP メールボックスを定期監視し、件名・宛先・送信元・本文のキーワードに一致したメールを Slack API `chat.postMessage` で通知する運用向けパイプラインです。

## できること

- `IMAP` メールボックスを 5 分ごとにポーリング
- `UID` ベースで前回処理位置を state JSON に保存
- 件名 / 宛先 / 送信元 / 本文 / 全文横断のキーワード条件でフィルタ
- 一致メールだけを Slack API トークン経由で通知
- 一致結果を `email-monitor-result.json` として Jenkins アーティファクトへ保存

## 前提

- Jenkins に `Username with password` 形式の IMAP 認証情報を登録済み
- Jenkins に `Secret text` 形式の Slack Bot/User Token を登録済み
- 重複通知を避けたい場合は `PVC_CLAIM_NAME` で永続ボリュームをマウントする

## 必要 Credential

- `mail-monitor-imap`
  - 種別: `Username with password`
  - 用途: IMAP ログイン
- `slack-bot-token`
  - 種別: `Secret text`
  - 用途: Slack API `chat.postMessage`
- `SLACK_CHANNEL`
  - 種別: Jenkins パラメータ
  - 用途: 通知先チャンネル名またはチャンネル ID

必要に応じてパラメータで別の Credential ID を指定できます。チャンネルは `#alerts` のような名前でも `C0123456789` のような ID でも指定できますが、運用上はチャンネル ID の固定を推奨します。

## キーワード一致ルール

- 各項目内は OR 条件です
  - 例: `SUBJECT_KEYWORDS=alert,error` なら件名に `alert` または `error` を含めば一致
- 項目どうしは AND 条件です
  - 例: `SUBJECT_KEYWORDS=invoice` と `RECIPIENT_KEYWORDS=ops@example.com` を両方指定した場合
  - 件名に `invoice` を含み、かつ宛先に `ops@example.com` を含むメールだけ通知
- `ANYWHERE_KEYWORDS` は件名 / 宛先 / 送信元 / 本文を連結した文字列に対して評価されます
- すべて部分一致・大文字小文字無視です

## 初回同期

`INITIAL_SYNC_MODE` で state が無い初回実行時の扱いを選べます。

- `latest`
  - 現在メールボックスにある最新 UID を state に保存して終了します
  - 既存メールをまとめて通知したくない通常運用向け
- `scan-all`
  - state が無くても既存メールを最初から走査します
  - 既存メールも含めて一度洗いたいとき向け

## 永続化

`PVC_CLAIM_NAME` を指定すると、Pod に PVC をマウントして `STATE_FILE_PATH` を永続化できます。

推奨例:

```text
PVC_CLAIM_NAME=mail-monitor-state-pvc
STATE_VOLUME_MOUNT_PATH=/mail-monitor-state
STATE_FILE_PATH=/mail-monitor-state/alerts-inbox.json
```

`PVC_CLAIM_NAME` を空欄にすると `emptyDir` を使うため、Pod 再作成時に state が消えます。その場合、同じメールを再通知する可能性があります。

## 主要パラメータ

- `IMAP_HOST`
  - 監視対象 IMAP サーバー
- `IMAP_PORT`
  - 通常は `993`
- `IMAP_USE_SSL`
  - `true` なら IMAPS
- `IMAP_MAILBOX`
  - 監視対象フォルダ。通常は `INBOX`
- `SUBJECT_KEYWORDS`
  - 件名キーワード
- `RECIPIENT_KEYWORDS`
  - 宛先キーワード。`To` / `Cc` / `Delivered-To` を対象
- `FROM_KEYWORDS`
  - 送信元キーワード
- `BODY_KEYWORDS`
  - 本文キーワード
- `ANYWHERE_KEYWORDS`
  - 全文横断キーワード
- `MAX_SLACK_ITEMS`
  - Slack 本文に載せる最大件数
- `SLACK_TOKEN_CREDENTIAL_ID`
  - Slack Token の Credential ID
- `SLACK_CHANNEL`
  - 通知先チャンネル名またはチャンネル ID
- `TEST_NOTIFICATIONS_ONLY`
  - IMAP を読まずに Slack 通知疎通だけ確認
- `DRY_RUN`
  - 一致結果を出すだけで Slack 通知も state 更新もしない

## 使い始めのおすすめ

1. `TEST_NOTIFICATIONS_ONLY=true` で Slack 疎通確認
2. `INITIAL_SYNC_MODE=latest` のまま `DRY_RUN=true` で期待どおりの条件か確認
3. `PVC_CLAIM_NAME` を設定して本運用へ切り替え
4. 必要なら `MAX_SLACK_ITEMS` や cron 間隔を調整

## 補足

- 現在の実装は Jenkins の cron によるポーリング型です
- IMAP IDLE のような常時接続型ではありません
- Slack 側は Incoming Webhook ではなく `chat.postMessage` を使用します
- より短い遅延や常駐接続が必要なら、Jenkins Pipeline ではなく専用 Deployment / CronJob への切り出しも検討してください
