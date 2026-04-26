/*
 * GitHub pull_request Webhook を受け取り、GitHub Copilot CLI で PR をレビューして
 * PR に通常コメントとして投稿する Declarative Pipeline です。
 *
 * 動作フロー:
 *   1. GitHub から pull_request イベント Webhook を受信する（opened / synchronize / reopened）
 *   2. ペイロードの $.repository.full_name からリポジトリを動的に取得する
 *   3. honoka4869/jenkins-maven-node イメージ（npm 内蔵）に @github/copilot をインストールする
 *   4. GitHub API で PR の diff を取得し、copilot -p でレビューを生成する
 *   5. GitHub Issues API で PR に通常コメントとして投稿する
 *
 * 必要な Jenkins Credentials（いずれも Kind: Secret text）:
 *   jqit-github-token         : GitHub Fine-grained Personal Access Token
 *                               用途: GitHub Copilot CLI (@github/copilot) の認証のみに使用する。
 *                               必要権限: "Copilot Requests"、および PR diff 取得のための
 *                                 Repository permissions の Contents: Read / Pull requests: Read。
 *                               ※ Copilot サブスクリプション（Individual / Business / Enterprise）
 *                                 に紐づくアカウントの PAT であることが前提。
 *   jqit-github-token-classic : GitHub Personal Access Token (Classic)
 *                               用途: 生成したレビュー本文を PR に issue comment として投稿する
 *                                     ための GitHub REST API 認証に使用する。
 *                               必要スコープ: repo。
 *                               ※ Fine-grained PAT では Issues / PR 書き込み権限の組み合わせで
 *                                 403 となるケースがあるため、コメント投稿側は Classic PAT に集約している。
 *
 * 必要な Jenkins プラグイン:
 *   - Generic Webhook Trigger Plugin
 *   - Kubernetes Plugin
 *
 * GitHub 側 Webhook 設定（レビュー対象の各リポジトリに設定）:
 *   Payload URL : https://<jenkins-host>/generic-webhook-trigger/invoke?token=github-copilot-pr-review
 *   Content type: application/json
 *   Events      : Pull requests
 *                 ※ $.repository.full_name でリポジトリを動的に判定するため
 *                   複数リポジトリで同一エンドポイントを共有できます
 */

pipeline {
    agent {
        kubernetes {
            defaultContainer 'build'
            // @github/copilot の npm インストールに十分なリソースを確保する
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: build
    image: honoka4869/jenkins-maven-node:latest
    command: [cat]
    tty: true
    resources:
      requests:
        cpu: "500m"
        memory: "1Gi"
      limits:
        cpu: "2"
        memory: "2Gi"
'''
        }
    }

    triggers {
        GenericTrigger(
            genericVariables: [
                // $.repository.full_name でどのリポジトリの PR かを動的に取得する
                [key: 'PR_NUMBER',      value: '$.pull_request.number'],
                [key: 'REPO_FULL_NAME', value: '$.repository.full_name'],
                [key: 'PR_HEAD_REF',    value: '$.pull_request.head.ref'],
                [key: 'PR_HEAD_SHA',    value: '$.pull_request.head.sha'],
                [key: 'PR_TITLE',       value: '$.pull_request.title'],
                [key: 'WEBHOOK_ACTION', value: '$.action'],
                [key: 'SENDER_LOGIN',   value: '$.sender.login'],
            ],
            causeString: 'GitHub PR #$PR_NUMBER ($WEBHOOK_ACTION) by $SENDER_LOGIN — $REPO_FULL_NAME',
            token: 'github-copilot-pr-review',
            // opened / synchronize / reopened 以外のアクションは無視する
            regexpFilterText: '$WEBHOOK_ACTION',
            regexpFilterExpression: '^(opened|synchronize|reopened)$'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
        timestamps()
        skipDefaultCheckout(true)
    }

    stages {

        stage('Validate Webhook') {
            steps {
                script {
                    if (!env.PR_NUMBER?.trim() || !env.REPO_FULL_NAME?.trim()) {
                        error('Webhook ペイロードから PR 番号またはリポジトリ名を取得できませんでした。Generic Webhook Trigger の設定を確認してください。')
                    }
                    echo "=== GitHub Copilot PR レビューパイプライン ==="
                    echo "リポジトリ  : ${env.REPO_FULL_NAME}"
                    echo "PR 番号     : ${env.PR_NUMBER}"
                    echo "タイトル    : ${env.PR_TITLE}"
                    echo "ブランチ    : ${env.PR_HEAD_REF}"
                    echo "SHA         : ${env.PR_HEAD_SHA}"
                    echo "アクション  : ${env.WEBHOOK_ACTION}"
                    echo "投稿者      : ${env.SENDER_LOGIN}"
                }
            }
        }

        stage('Setup GitHub Copilot CLI') {
            steps {
                sh '''#!/bin/sh
set -e
echo "--- @github/copilot をグローバルインストール ---"
npm install -g @github/copilot

echo "--- バージョン確認 ---"
copilot --version || true
'''
            }
        }

        stage('Review & Post Comment') {
            steps {
                // Copilot CLI 認証用（Fine-grained PAT）と PR コメント投稿用（Classic PAT）を
                // 別 Credential として同時にバインドし、それぞれの役割で使い分ける。
                withCredentials([
                    string(credentialsId: 'jqit-github-token',         variable: 'COPILOT_TOKEN'),
                    string(credentialsId: 'jqit-github-token-classic', variable: 'GITHUB_TOKEN'),
                ]) {
                    sh '''#!/bin/sh
set -e
# Copilot CLI は COPILOT_GITHUB_TOKEN > GH_TOKEN > GITHUB_TOKEN の順で認証トークンを参照する。
# withCredentials で GITHUB_TOKEN には Classic PAT（コメント投稿用）が入るため、
# Copilot CLI 側は COPILOT_GITHUB_TOKEN / GH_TOKEN へ Fine-grained PAT を明示的に渡し、
# コメント投稿や PR diff 取得用の API 呼び出しとトークンを分離する。
export COPILOT_GITHUB_TOKEN="${COPILOT_TOKEN}"
export GH_TOKEN="${COPILOT_TOKEN}"
TRUNCATED_NOTE=""

echo "--- copilot CLI 認証状態の確認（失敗してもパイプラインは継続）---"
copilot --version || true

echo "--- PR #${PR_NUMBER} の差分を取得（GitHub API、Classic PAT で認証）---"
curl -fsSL \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github.v3.diff" \
    "https://api.github.com/repos/${REPO_FULL_NAME}/pulls/${PR_NUMBER}" \
    > /tmp/pr_diff.patch

DIFF_SIZE=$(wc -c < /tmp/pr_diff.patch)
echo "差分サイズ: ${DIFF_SIZE} bytes"

# copilot -p の引数長上限を考慮して先頭 10,000 バイトに切り詰める
if [ "${DIFF_SIZE}" -gt 10000 ]; then
    head -c 10000 /tmp/pr_diff.patch > /tmp/pr_diff_trim.patch
    mv /tmp/pr_diff_trim.patch /tmp/pr_diff.patch
    TRUNCATED_NOTE=" ※差分が大きいため先頭 10,000 バイトのみレビュー対象"
    echo "差分を切り詰めました。"
fi

DIFF_CONTENT=$(cat /tmp/pr_diff.patch)

echo "--- Copilot CLI でレビューを生成（-p フラグで非インタラクティブ実行）---"
# stdout / stderr をそれぞれファイルへ分離し、失敗時は stderr をジョブログへ出力する。
# 以前の `$(cmd 2>&1) || fallback` 方式は失敗時のエラー詳細を握り潰してしまうため採用しない。
set +e
copilot -p "以下の Git diff をコードレビューしてください。
問題点・改善提案・セキュリティリスクを日本語の箇条書きで指摘してください。
問題がなければ「問題なし」とだけ回答してください。

PR: ${REPO_FULL_NAME}#${PR_NUMBER}
タイトル: ${PR_TITLE}

${DIFF_CONTENT}" > /tmp/copilot_stdout.txt 2> /tmp/copilot_stderr.txt
COPILOT_RC=$?
set -e

if [ "${COPILOT_RC}" -eq 0 ]; then
    REVIEW=$(cat /tmp/copilot_stdout.txt)
else
    echo "--- Copilot CLI が異常終了しました (rc=${COPILOT_RC}) ---"
    echo "--- stderr ---"
    cat /tmp/copilot_stderr.txt || true
    echo "--- stdout ---"
    cat /tmp/copilot_stdout.txt || true
    # PR コメント本文には先頭の数行だけ載せ、詳細はジョブログ側で確認する運用とする
    ERR_HEAD=$(head -c 500 /tmp/copilot_stderr.txt 2>/dev/null || true)
    REVIEW="_GitHub Copilot CLI によるレビュー生成に失敗しました（rc=${COPILOT_RC}）。Jenkins ジョブログを確認してください。_

<details><summary>エラー出力（先頭 500 byte）</summary>

\\`\\`\\`
${ERR_HEAD}
\\`\\`\\`

</details>"
fi

echo "--- コメント本文を生成 ---"
# ヘッダー（変数展開なし）
cat > /tmp/review_body.txt << 'HEADER_EOF'
## GitHub Copilot CLI レビュー
HEADER_EOF

# メタ情報と本文（変数展開あり）
cat >> /tmp/review_body.txt << META_EOF

**リポジトリ**: \\`${REPO_FULL_NAME}\\`
**PR**: [#${PR_NUMBER} — ${PR_TITLE}](https://github.com/${REPO_FULL_NAME}/pull/${PR_NUMBER})
**ブランチ**: \\`${PR_HEAD_REF}\\` / SHA: \\`${PR_HEAD_SHA}\\`${TRUNCATED_NOTE}

---

${REVIEW}

---
*このコメントは Jenkins + [@github/copilot](https://www.npmjs.com/package/@github/copilot) によって自動生成されました。*
META_EOF

echo "--- PR #${PR_NUMBER} にコメントを投稿（GitHub Issues API）---"
# Node.js で JSON を安全に組み立てて投稿する（改行・引用符などの特殊文字を適切にエスケープ）
node -e "
const fs = require('fs');
const body = fs.readFileSync('/tmp/review_body.txt', 'utf8');
process.stdout.write(JSON.stringify({ body }));
" > /tmp/comment_payload.json

curl -sS -X POST \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/json" \
    -o /tmp/comment_response.json \
    -w "HTTP_STATUS=%{http_code}\\n" \
    "https://api.github.com/repos/${REPO_FULL_NAME}/issues/${PR_NUMBER}/comments" \
    -d @/tmp/comment_payload.json \
    > /tmp/comment_http.txt 2>&1

HTTP_STATUS=$(grep -oE 'HTTP_STATUS=[0-9]+' /tmp/comment_http.txt | tail -1 | cut -d= -f2)
echo "--- GitHub Issues API HTTP ステータス: ${HTTP_STATUS} ---"

if [ "${HTTP_STATUS}" != "201" ]; then
    echo "--- コメント投稿に失敗しました (HTTP ${HTTP_STATUS}) ---"
    echo "--- レスポンス本文 ---"
    cat /tmp/comment_response.json || true
    echo ""
    echo "--- curl 出力 ---"
    cat /tmp/comment_http.txt || true
    exit 22
fi

COMMENT_ID=$(node -e "
const r = JSON.parse(require('fs').readFileSync('/tmp/comment_response.json', 'utf8'));
process.stdout.write(String(r.id || ''));
")

if [ -n "${COMMENT_ID}" ]; then
    echo "コメント投稿完了 (ID: ${COMMENT_ID})"
    echo "URL: https://github.com/${REPO_FULL_NAME}/pull/${PR_NUMBER}#issuecomment-${COMMENT_ID}"
else
    echo "コメント投稿に失敗しました"
    cat /tmp/comment_response.json
    exit 1
fi
'''
                }
            }
        }
    }

    post {
        success {
            echo "PR #${env.PR_NUMBER} (${env.REPO_FULL_NAME}) のレビューコメントを正常に投稿しました。"
        }
        failure {
            echo "PR #${env.PR_NUMBER} (${env.REPO_FULL_NAME}) のレビュー処理に失敗しました。ログを確認してください。"
        }
    }
}
