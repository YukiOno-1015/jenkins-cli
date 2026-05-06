/*
 * Nexus Raw リポジトリへファイルをアップロードする Shared Library 関数。
 *
 * 元ネタ: scripts/nexus-raw-upload.sh の Groovy 移植版。
 * Jenkins 上の K8s/シェル付き Agent で利用することを想定し、curl で
 * `https://<host>/repository/<repo>/<remote-path>` へ PUT する。
 *
 * 設計方針:
 * - 認証は必ず Jenkins の Username with password Credential 経由
 *   （対話プロンプトや ~/.netrc は使わない）
 * - 単一ファイル / 複数ファイルの両方をサポート
 * - 1 件でも失敗したらジョブを fail させる（CI からは中途半端な成功を出さない）
 *
 * 使用例:
 *   // 単一ファイル、リモートパス明示
 *   nexusRawUpload(
 *     credentialsId: 'nexus-raw',
 *     file: 'build/app.tar.gz',
 *     remotePath: 'eclipse/2026-03/app.tar.gz'
 *   )
 *
 *   // 単一ファイル、basename をリポジトリルートに置く
 *   nexusRawUpload(
 *     credentialsId: 'nexus-raw',
 *     file: 'build/app.tar.gz'
 *   )
 *
 *   // 複数ファイルを共通ディレクトリへ
 *   nexusRawUpload(
 *     credentialsId: 'nexus-raw',
 *     files: ['build/a.tar.gz', 'build/b.tar.gz'],
 *     remoteDir: 'eclipse/2026-03'
 *   )
 *
 * 引数:
 *   file または files (必須): ローカルファイルパス（単一文字列 または List<String>）
 *   remotePath (任意)    : 単一ファイル時のリモート完全パス
 *   remoteDir (任意)     : 配置先ディレクトリ。指定時は basename を末尾に付与する
 *   credentialsId (任意) : Username with password の Jenkins Credential ID（既定 'nexus'）
 *   nexusHost (任意)     : 既定 'nexus-cli.sk4869.info'（LAN 直、上限なし）
 *   nexusRepo (任意)     : 既定 'raw-tools'
 *
 * 戻り値:
 *   [total: N, ok: N, failed: N, uploads: [[file, remotePath, httpCode, ok], ...]]
 */
def call(Map args = [:]) {
  String credentialsId = ((args.credentialsId ?: 'nexus') as String).trim()
  String nexusHost = ((args.nexusHost ?: 'nexus-cli.sk4869.info') as String).trim()
  String nexusRepo = ((args.nexusRepo ?: 'raw-tools') as String).trim()

  // file / files の両入口を受けて 1 つの List に正規化する
  List<String> files = []
  if (args.files != null) {
    if (args.files instanceof List) {
      files.addAll((args.files as List).collect { it?.toString() }.findAll { it })
    } else {
      files << args.files.toString()
    }
  }
  if (args.file != null) {
    files << args.file.toString()
  }
  files = files.collect { it.trim() }.findAll { it }
  if (files.isEmpty()) {
    error('nexusRawUpload: file または files の指定が必要です。')
  }

  String remoteDir = args.remoteDir != null
      ? args.remoteDir.toString().replaceAll('^/+', '').replaceAll('/+$', '')
      : null
  String remotePath = args.remotePath != null
      ? args.remotePath.toString().replaceAll('^/+', '')
      : null

  if (remotePath && files.size() > 1) {
    error('nexusRawUpload: 複数ファイル指定時は remotePath ではなく remoteDir を使用してください。')
  }

  // (local, remote) のペアに展開
  List<Map> uploads = files.collect { String localFile ->
    String basename = localFile.replaceAll('^.*/', '')
    String resolved
    if (remotePath) {
      resolved = remotePath
    } else if (remoteDir) {
      resolved = "${remoteDir}/${basename}"
    } else {
      resolved = basename
    }
    [local: localFile, remote: resolved]
  }

  echo '========================================='
  echo 'Nexus Raw アップロード'
  echo '========================================='
  echo "Host : ${nexusHost}"
  echo "Repo : ${nexusRepo}"
  echo "対象 : ${uploads.size()} 件"
  uploads.each { Map u ->
    echo "  - ${u.local} → ${u.remote}"
  }
  echo '========================================='

  int ok = 0
  int failed = 0
  List<Map> results = []

  withCredentials([
    usernamePassword(
      credentialsId: credentialsId,
      usernameVariable: 'NEXUS_USER',
      passwordVariable: 'NEXUS_PASS'
    )
  ]) {
    uploads.each { Map u ->
      String localFile = u.local
      String remote = u.remote
      String url = "https://${nexusHost}/repository/${nexusRepo}/${remote}"

      if (!fileExists(localFile)) {
        echo "✗ ファイルが存在しません: ${localFile}"
        failed++
        results << [file: localFile, remotePath: remote, httpCode: '000', ok: false]
        return
      }

      echo "→ ${localFile}"
      echo "  ${url}"

      // ローカルパス・URL は Groovy 側で安全に single-quote する。
      // 認証情報は Groovy には渡さず、bash の env 変数として参照する（マスキング維持）。
      String quotedLocal = shellQuote(localFile)
      String quotedUrl = shellQuote(url)

      String httpCode = sh(
        returnStdout: true,
        script: """#!/bin/bash
set +e
respFile="\$(mktemp /tmp/nexus-upload-resp.XXXXXX)"
http=\$(curl -sSL \\
  -o "\$respFile" \\
  -w '%{http_code}' \\
  -u "\$NEXUS_USER:\$NEXUS_PASS" \\
  --upload-file ${quotedLocal} \\
  ${quotedUrl})
rc=\$?
if [ "\$rc" -ne 0 ] || [ -z "\$http" ]; then
  http=000
fi
case "\$http" in
  20[0-9]) ;;
  *)
    echo "--- response body ---" >&2
    cat "\$respFile" >&2 2>/dev/null || true
    echo >&2
    ;;
esac
rm -f "\$respFile" 2>/dev/null || true
printf '%s' "\$http"
"""
      ).trim()

      boolean uploadOk = httpCode ==~ /^20[0-9]$/
      if (uploadOk) {
        echo "  ✓ アップロード完了 (HTTP ${httpCode})"
        ok++
      } else if (httpCode == '401' || httpCode == '403') {
        echo "  ✗ 認証エラー (HTTP ${httpCode})"
        failed++
      } else {
        echo "  ✗ アップロード失敗 (HTTP ${httpCode})"
        failed++
      }

      results << [file: localFile, remotePath: remote, httpCode: httpCode, ok: uploadOk]
    }
  }

  echo '========================================='
  echo "サマリ: ${ok}/${uploads.size()} 成功, ${failed} 失敗"
  echo '========================================='

  if (failed > 0) {
    error("nexusRawUpload: ${failed} 件のアップロードに失敗しました。")
  }

  return [total: uploads.size(), ok: ok, failed: failed, uploads: results]
}

/**
 * 任意の文字列を bash の single-quote で安全に囲む。
 * 単一引用符自身は '\'' でエスケープする。
 */
def shellQuote(String s) {
  return "'" + (s ?: '').replace("'", "'\\''") + "'"
}
