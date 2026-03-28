# Jenkins Pipeline: machost自動アップデート

## 機能概要
- Jenkins Pipelineで複数のmachostサーバに対し、ssh経由でapt update/upgrade等の自動アップデートを実行する。
- サーバリストはGroovy配列で管理。
- .ssh/configとmacOSキーチェーンを活用し、Jenkins認証情報に依存しない。

## ベストプラクティス
- SSHユーザー名・コマンドは関数の引数で渡す（スコープエラー防止）。
- ssh-agentやJenkins認証情報は不要。
- サーバ追加・削除は配列編集のみでOK。
- エラーは各ホストごとにcatchし、全体の停止を防ぐ。
- Jenkinsのagentはmachost自身で動作させる。

## 参考実装
- src/update-machosts.groovy

---

# Jenkins Pipeline: Cloudflare Allowlist管理

## 機能概要
- Cloudflare APIを用いてJenkinsサーバのIPをAllowlistに自動登録。
- Jenkins CredentialsでAPIトークンを安全に管理。
- SCM checkoutやAPI通信のリトライ・タイムアウト・排他制御を実装。

## ベストプラクティス
- withCloudflareCredentialsクロージャで認証情報の柔軟な取得。
- checkout時はretry/timeout/disableConcurrentBuildsを活用。
- APIトークンはJenkins Credentialsまたは環境変数で管理。
- ステートレス設計（Cloudflare現状取得→差分更新）。

## 参考実装
- src/declarative-pipeline.groovy

---

# Jenkins Agent: launchd自動起動テンプレート

## 機能概要
- macOSのlaunchdでJenkins Agentを自動起動・自動再起動。
- ログ出力・KeepAlive・環境変数設定に対応。

## ベストプラクティス
- plistはdocs/templates/local.Jenkins.Agent.launchd.plistに配置。
- README.mdに導入手順を明記。
- Jenkins Agentのバイナリ・JARパスは絶対パスで指定。
- KeepAlive/RunAtLoadで自動復旧。

## 参考実装
- docs/templates/local.Jenkins.Agent.launchd.plist
- README.md
