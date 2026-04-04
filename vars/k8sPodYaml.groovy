/*
 * Jenkins Kubernetes agent 用の Pod YAML を組み立てるヘルパーです。
 *
 * 設計方針:
 * - 呼び出し引数で個別上書きできるようにする
 * - 未指定項目は `repositoryConfig()` のリポジトリ別設定を優先する
 * - 最後に安全なデフォルト値で補完し、呼び出し元の定義を簡潔に保つ
 */

/**
 * Pod テンプレート文字列を返す。
 * `image`, `imagePullSecret`, `cpuRequest`, `memRequest`, `cpuLimit`, `memLimit` を上書き可能。
 */
def call(Map args = [:]) {
    // repositoryConfigから設定を取得（オプション）
    def config = null
    if (args.repoName || args.repoUrl) {
        def identifier = args.repoName ?: args.repoUrl
        config = repositoryConfig(identifier)
    }
    
    // 引数 > repositoryConfig > デフォルト値 の優先順位
    def image = args.get('image', config?.k8s?.image ?: 'honoka4869/jenkins-maven-node:latest')
    def imagePullSecret = args.get('imagePullSecret', 'docker-hub')
    def cpuReq = args.get('cpuRequest', config?.k8s?.cpuRequest ?: '500m')
    def memReq = args.get('memRequest', config?.k8s?.memRequest ?: '2Gi')
    def cpuLim = args.get('cpuLimit', config?.k8s?.cpuLimit ?: '2')
    def memLim = args.get('memLimit', config?.k8s?.memLimit ?: '4Gi')

    // YAML生成をより保守性の高い形式で
    return """---
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins: jenkins-k8s-maven-node
spec:
  imagePullSecrets:
    - name: ${imagePullSecret}
  containers:
    - name: build
      image: ${image}
      command:
        - cat
      tty: true
      resources:
        requests:
          cpu: "${cpuReq}"
          memory: "${memReq}"
        limits:
          cpu: "${cpuLim}"
          memory: "${memLim}"
      env:
        - name: MAVEN_OPTS
          value: "-Xmx1024m -Xms256m -XX:+UseContainerSupport"
  restartPolicy: Never
"""
}