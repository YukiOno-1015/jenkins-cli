pipeline {
  agent { label 'machost' }
  triggers { cron('H/10 * * * *') }

  environment {
    HOSTNAME = 'jenkins-svc.sk4869.info'
    RULE_DESC = 'allowlist-jenkins-svc'
    IP_SOURCE_URL = 'https://ifconfig.me'
  }

  stages {
    stage('Update Cloudflare allowlist') {
      steps {
        withCredentials([
          string(credentialsId: 'CF_API_TOKEN', variable: 'CF_API_TOKEN'),
          string(credentialsId: 'CF_ZONE_ID', variable: 'CF_ZONE_ID')
        ]) {
          sh '''
            set -euo pipefail
            chmod +x /Volumes/HDD/work/jenkins-cli/cf_update_jenkins_allowlist.sh
            /Volumes/HDD/work/jenkins-cli/cf_update_jenkins_allowlist.sh
          '''
        }
      }
    }
  }

  post {
    failure {
      echo 'Cloudflare allowlist update failed. Check console log.'
    }
  }
}