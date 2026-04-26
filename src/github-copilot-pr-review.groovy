/*
 * GitHub pull_request Webhook を受け取り、対象 PR にレビュアーとして
 * GitHub Copilot Code Review（bot）を割り当てる Declarative Pipeline です。
 *
 * 設計方針:
 *   - Jenkins 側で Copilot CLI を実行してレビューを生成・コメント投稿する方式は廃止し、
 *     GitHub 公式の Copilot Code Review に肩代わりさせる。
 *   - Jenkins からは PR のレビュアーに `copilot-pull-request-reviewer` を追加するだけ。
 *   - レビュー本文は Copilot Code Review が PR コメント／レビューコメントとして自動投稿する。
 *
 * 利点:
 *   - 必要な PAT 権限が Pull requests: write のみで済む（Issues 権限不要）。
 *   - Copilot CLI の実行・差分の整形・コメント投稿のロジックがすべて不要になる。
 *   - レビュー品質・更新は GitHub 側に追従する。
 *
 * 動作フロー:
 *   1. GitHub から pull_request イベント Webhook を受信する（opened / synchronize / reopened）
 *   2. ペイロードの $.repository.full_name からリポジトリを動的に取得する
 *   3. GitHub Pull Requests API でレビュアーに `copilot-pull-request-reviewer` を追加する
 *
 * 必要な Jenkins Credentials（Kind: Secret text）:
 *   jqit-github-token : GitHub Personal Access Token
 *                       Fine-grained PAT 推奨
 *                       必要権限: Repository permissions
 *                                 - Pull requests: Read and write
 *                                 - Metadata: Read-only
 *                       ※ 対象リポジトリの所有者と PAT の Resource owner が異なる場合は
 *                         事前にコラボレーター招待・承認を済ませること
 *                       ※ Copilot Code Review はリポジトリ側で有効化が必要
 *                         （Settings → Code & automation → Copilot code review）
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
            // curl だけ動けばよいので軽量な構成にとどめる
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
        cpu: "100m"
        memory: "256Mi"
      limits:
        cpu: "500m"
        memory: "512Mi"
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
            // synchronize（追加コミット）でも再リクエストされるが、Copilot 側で重複は適切にハンドリングされる
            regexpFilterText: '$WEBHOOK_ACTION',
            regexpFilterExpression: '^(opened|synchronize|reopened)$'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 5, unit: 'MINUTES')
        timestamps()
        skipDefaultCheckout(true)
    }

    environment {
        // Copilot Code Review の bot ユーザー名（GitHub 側で固定）
        COPILOT_REVIEWER_LOGIN = 'copilot-pull-request-reviewer'
    }

    stages {

        stage('Validate Webhook') {
            steps {
                script {
                    if (!env.PR_NUMBER?.trim() || !env.REPO_FULL_NAME?.trim()) {
                        error('Webhook ペイロードから PR 番号またはリポジトリ名を取得できませんでした。Generic Webhook Trigger の設定を確認してください。')
                    }
                    echo "=== GitHub Copilot Code Review レビュアー指定パイプライン ==="
                    echo "リポジトリ      : ${env.REPO_FULL_NAME}"
                    echo "PR 番号         : ${env.PR_NUMBER}"
                    echo "タイトル        : ${env.PR_TITLE}"
                    echo "ブランチ        : ${env.PR_HEAD_REF}"
                    echo "SHA             : ${env.PR_HEAD_SHA}"
                    echo "アクション      : ${env.WEBHOOK_ACTION}"
                    echo "投稿者          : ${env.SENDER_LOGIN}"
                    echo "指定レビュアー  : ${env.COPILOT_REVIEWER_LOGIN}"
                }
            }
        }

        stage('Request Copilot Review') {
            steps {
                withCredentials([string(credentialsId: 'jqit-github-token', variable: 'GITHUB_TOKEN')]) {
                    sh '''#!/bin/sh
set -e

echo "--- PR #${PR_NUMBER} のレビュアーに Copilot Code Review を追加 ---"

# レビュアー追加 API: POST /repos/{owner}/{repo}/pulls/{number}/requested_reviewers
# 既に同一レビュアーがリクエスト済みの場合 GitHub は現在の状態を 201 で返すため、
# synchronize 等で複数回呼ばれても安全に冪等となる。
curl -sS -X POST \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/json" \
    -o /tmp/reviewer_response.json \
    -w "HTTP_STATUS=%{http_code}\\n" \
    "https://api.github.com/repos/${REPO_FULL_NAME}/pulls/${PR_NUMBER}/requested_reviewers" \
    -d "{\\"reviewers\\":[\\"${COPILOT_REVIEWER_LOGIN}\\"]}" \
    > /tmp/reviewer_http.txt 2>&1

HTTP_STATUS=$(grep -oE 'HTTP_STATUS=[0-9]+' /tmp/reviewer_http.txt | tail -1 | cut -d= -f2)
echo "--- GitHub API HTTP ステータス: ${HTTP_STATUS} ---"

# 201 Created または 200 OK を成功扱いとする
case "${HTTP_STATUS}" in
    200|201)
        echo "--- レビュアー指定に成功しました ---"
        echo "PR URL: https://github.com/${REPO_FULL_NAME}/pull/${PR_NUMBER}"
        ;;
    422)
        # 422 はレビュアー追加対象として無効な場合などに返る
        # 例: Copilot Code Review がリポジトリ／組織で未有効化のケース
        echo "--- レビュアー指定に失敗しました (HTTP 422) ---"
        echo "リポジトリ側で Copilot Code Review が有効化されているか確認してください。"
        echo "--- レスポンス本文 ---"
        cat /tmp/reviewer_response.json || true
        exit 22
        ;;
    *)
        echo "--- レビュアー指定に失敗しました (HTTP ${HTTP_STATUS}) ---"
        echo "--- レスポンス本文 ---"
        cat /tmp/reviewer_response.json || true
        echo ""
        echo "--- curl 出力 ---"
        cat /tmp/reviewer_http.txt || true
        exit 22
        ;;
esac
'''
                }
            }
        }
    }

    post {
        success {
            echo "PR #${env.PR_NUMBER} (${env.REPO_FULL_NAME}) に Copilot Code Review をリクエストしました。"
        }
        failure {
            echo "PR #${env.PR_NUMBER} (${env.REPO_FULL_NAME}) のレビュアー指定に失敗しました。ログを確認してください。"
        }
    }
}
