def call(Map args = [:]) {
    def image = args.get('image', 'honoka4869/jenkins-maven-node:latest')
    def imagePullSecret = args.get('imagePullSecret', 'dockerhub-jenkins-agent')
    def cpuReq = args.get('cpuRequest', '500m')
    def memReq = args.get('memRequest', '2Gi')
    def cpuLim = args.get('cpuLimit', '2')
    def memLim = args.get('memLimit', '4Gi')

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