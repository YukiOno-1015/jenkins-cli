# GitHub Copilot PR レビューパイプライン

[`src/github-copilot-pr-review.groovy`](../src/github-copilot-pr-review.groovy) は、GitHub の `pull_request` Webhook を受け取り、GitHub Copilot CLI (`@github/copilot`) で PR の差分をレビューし、その結果を PR の通常コメントとして投稿する Jenkins Declarative Pipeline です。

## 概要

- トリガー: GitHub `pull_request` Webhook（`opened` / `synchronize` / `reopened` のみ）
- 実行環境: Kubernetes Pod（`honoka4869/jenkins-maven-node:latest`、npm 内蔵）
- レビュー対象: GitHub API から取得した PR の diff（先頭 100,000 バイトまで）
- 出力先: 対象 PR の Issue コメント

`$.repository.full_name` でリポジトリを動的に判定するため、複数リポジトリで同一 Webhook エンドポイントを共有できます。

## 必要な Jenkins Credentials

| ID | 種別 | 用途 |
| --- | --- | --- |
| `github-copilot-pr-review-secret` | Secret text | **Webhook HMAC 署名検証用シークレット**。GitHub リポジトリの Webhook 設定の "Secret" フィールドに入力するランダム文字列と同じ値を登録する。GenericTrigger の `secretToken` に渡し、`X-Hub-Signature-256` ヘッダーで署名を検証することで偽造ペイロードを拒否する。 |
| `jqit-github-token` | Secret text | GitHub Fine-grained Personal Access Token。**Copilot CLI 認証専用** (`COPILOT_GITHUB_TOKEN` / `GH_TOKEN` に展開)。Copilot サブスクリプションが付与されたユーザーのトークンを設定する。Repository permissions: `Pull requests: Read`, `Contents: Read`, および `Copilot Requests` を有効化する。 |
| `jqit-github-token-classic` | Secret text | GitHub Personal Access Token (Classic, scope=`repo`)。**PR への issue comment 投稿および PR diff 取得用** (`GITHUB_TOKEN`)。Fine-grained PAT では Issues / PR 書き込み権限の組み合わせで 403 が発生したため、書き込み API は Classic PAT に集約している。 |

`copilot` CLI は `COPILOT_GITHUB_TOKEN` を優先して認証に利用します。本パイプラインでは Fine-grained PAT をバインドした中間変数 `COPILOT_TOKEN` を `COPILOT_GITHUB_TOKEN` および `GH_TOKEN` へエクスポートしています (コメント投稿用の Classic PAT をバインドした `GITHUB_TOKEN` とは独立しています)。

## 必要な Jenkins プラグイン

- Generic Webhook Trigger Plugin
- Kubernetes Plugin

## GitHub Webhook 設定

レビュー対象の各リポジトリに以下を設定します。

- Payload URL: `https://<jenkins-host>/generic-webhook-trigger/invoke?token=github-copilot-pr-review`
- Content type: `application/json`
- Secret: `<任意のランダム文字列>` ← Jenkins credential `github-copilot-pr-review-secret` と同じ値を設定する（必須）
- Events: `Pull requests`

`?token=...` の URL だけでは第三者が偽の `pull_request` ペイロードを送れるため、必ず Secret を設定して `X-Hub-Signature-256` ヘッダーによる HMAC-SHA256 署名検証を有効にすること。

## 処理フロー

1. Webhook ペイロードから PR 番号・リポジトリ名・ブランチ・SHA 等を抽出する。
2. `npm install -g @github/copilot` で Copilot CLI を導入する。
3. GitHub API から `Accept: application/vnd.github.v3.diff` で PR の差分を取得する。
4. `copilot -p "<プロンプト>"` で非インタラクティブにレビュー結果を生成する。
5. GitHub Issues API (`POST /repos/{owner}/{repo}/issues/{issue_number}/comments`) でレビューコメントを投稿する。

## 注意事項

- `copilot -p` はプロンプト全体を 1 個の argv 引数として渡します。Linux カーネルには 1 引数あたりの上限 `MAX_ARG_STRLEN` (典型的に 128KB = 131072 バイト) があり、`ARG_MAX` (「全引数合計」の上限 ≍ 2MB) に収まっていても 1 引数が 128KB を超えると `execve()` が `E2BIG` (Argument list too long) で失敗します。このため、差分は先頭 100,000 バイト (約 100KB) に切り詰め、プロンプト組み立て後に `printf | wc -c` でサイズを測定します。`MAX_ARG_STRLEN` 以上の場合は差分をさらに再切り詰めして再試行し、それでも超過する場合は `SKIP_COPILOT=1` にして「差分が大きすぎるため手動レビューをお願いします」というフォールバックコメントを PR に投稿し、ジョブは **成功** として終了します（未レビューをサイレントに見逃さないよう、コメント本文で明示的に通知します）。
- 差分の切り詰めは `head -c` でバイト単位に切った上で、可能であれば `iconv -f UTF-8 -t UTF-8 -c` を通して UTF-8 として整合した状態に正規化します。これは末尾の数バイトがマルチバイト文字の途中で切れて Copilot 入力が破損するのを避けるためですが、末尾 1〜数文字が欠落する可能性があります (極端なケースでは識別子・演算子の末尾が欠ける可能性あり)。`iconv` が実行イメージに無い場合は `head -c` のみで処理を継続し、コメント末尾の注記に方式を併記します。
- Copilot が学習データ上の時刻 (2025 年付近) を「現在」とみなしてしまうケースに備え、ジョブ実行時の現在日時 (UTC / JST) をプロンプトに明示注入し、「カレンダー上の日付が学習データより未来」等の認識ズレに起因する誤指摘のみ抑制します。依存解決に失敗し得る「実在しない / 未公開のライブラリバージョン」や「存在しないリリースタグ参照」は通常通り指摘対象とします。ただし日時注入は補助情報であり、Copilot の時刻バイアスを完全に解消するものではありません。また Copilot は実行時にネットワークアクセスを行わないため、学習データに含まれない最新リリースバージョンの実在性までは検証できない点に留意してください。
- JST は zoneinfo (Asia/Tokyo) を必要としない POSIX TZ 文字列 `JST-9` (UTC+9 固定オフセット) で生成しており、tzdata 未導入のコンテナイメージでも動作します。
- PR diff および PR タイトルは外部入力 (悪意のある文言を埋め込み得る) として扱い、実行ごとに `/dev/urandom` から生成した 24 桁のランダム hex 文字列 (`DELIM`) を境界タグとして使用しています。予測不能な区切り文字列を使うことで、PR タイトルや diff に任意の文字列を埋め込んでもプロンプト境界を改ざんすることが実質不可能になります (固定タグによるインジェクション攻撃への対策)。完全防御ではないため、レビュー結果は最終的に人間が確認する運用を維持してください。
- レビュー生成に失敗した場合は手動レビューを促すフォールバックコメントを投稿します。
- 投稿コメントは `node -e` で JSON を組み立ててから `curl -d @file` で送信しており、改行や引用符を含む本文でも安全に投稿できます。
