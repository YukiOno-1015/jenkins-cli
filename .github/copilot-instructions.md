# jenkins-cli プロジェクト方針

本リポジトリは Jenkins Pipeline（`src/*.groovy`）と Shared Library（`vars/*.groovy`）、運用ドキュメント（`docs/`）で構成される、Jenkins 周辺の自動化スクリプト群を管理するリポジトリ。

共通の作業ルール（コミット規約・PR 規約・SerenaMCP・SonarQube など）はユーザーグローバル設定（`global-preferences.instructions.md`）に従う。本ファイルでは jenkins-cli 固有の事項のみを記載する。

## リポジトリ構成

- `src/` … 個別 Jenkins Pipeline 定義（Declarative Pipeline）。1 ファイル 1 ジョブ相当。
- `vars/` … Shared Library。`call(...)` を公開する Groovy ファイル群。
- `docs/` … 運用・設計ドキュメント。新規パイプライン追加時はここに概要を追記する。
- `.github/skills/` … パイプライン別の Copilot スキル定義（既存）。

## コーディング規約（Groovy / Jenkinsfile）

- Declarative Pipeline を基本構文とする。`script {}` は必要箇所のみ。
- パイプライン共通処理は重複させず、`vars/` の Shared Library に集約する。
- `echo` / `error` などのメッセージは原則日本語。
- 認証情報は必ず Jenkins Credentials を `withCredentials` / `credentials()` で扱う。コード内に直書きしない。

## ドキュメント

- 新規パイプライン・大きな仕様変更は `docs/` 配下に Markdown を追加または更新する。
- `.markdownlint-cli2.yaml` のルールに準拠する（行長・見出し階層など）。

## 関連スキル

`.github/skills/` 配下に既存スキルがある場合は、対象パイプライン編集時にそのスキルの指示を優先する。

- `cloudflare-allowlist-pipeline` … `src/declarative-pipeline.groovy`
- `jenkins-machost-updates` … `src/update-machosts.groovy`
- `proxmox-host-maintenance` … `src/update-proxmox-hosts.groovy`
- `macos-jenkins-agent-launchd` … `docs/templates/local.Jenkins.Agent.launchd.plist`

## 補足

- 静的解析: Groovy は SonarQube Community Edition の対象外のため、SonarQube 連携は実施しない。SonarLint の指摘がある場合のみ対応する。
- テスト: ユニットテスト基盤は未整備。変更時は対象パイプラインのドライラン・構文確認（`groovy -e` または Jenkins 上の Replay）で確認する。
