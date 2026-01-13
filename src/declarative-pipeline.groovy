pipeline {
  agent { label 'machost' }
  triggers { cron('H/10 * * * *') }

  environment {
    HOSTNAME = 'jenkins-svc.sk4869.info'
    RULE_DESC = 'allowlist-jenkins-svc'
    IP_SOURCE_URL = 'https://ifconfig.me'
    SCRIPT_PATH = "${WORKSPACE}/scripts/cf_update_jenkins_allowlist.sh"
  }

  stages {
    stage('Pull Latest Changes') {
      steps {
        sh '''
          set -euo pipefail
          
          echo "Pulling latest changes from repository"
          git pull origin main
          
          echo "Current commit:"
          git log -1 --oneline
        '''
      }
    }
    
    stage('Update Cloudflare allowlist') {
      steps {
        withCredentials([
          string(credentialsId: 'CF_API_TOKEN', variable: 'CF_API_TOKEN'),
          string(credentialsId: 'CF_ZONE_ID', variable: 'CF_ZONE_ID')
        ]) {
          sh '''
            set -euo pipefail
            
            if [ ! -f "${SCRIPT_PATH}" ]; then
              echo "ERROR: Script not found at ${SCRIPT_PATH}"
              exit 1
            fi
            
            chmod +x "${SCRIPT_PATH}"
            "${SCRIPT_PATH}"
          '''
        }
      }
    }
  }

  post {
    success {
      echo 'Cloudflare allowlist updated successfully'
    }
    failure {
      echo 'Cloudflare allowlist update failed. Check console log for details.'
    }
  }
}