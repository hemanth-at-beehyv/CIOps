library 'ci-libs'

def call(Map pipelineParams) {

podTemplate(yaml: """
kind: Pod
metadata:
  name: egov-deployer
spec:
  containers:
  - name: egov-deployer
    image: egovio/egov-deployer:helmfile-integration
    command:
    - cat
    tty: true
    env:  
      - name: "GOOGLE_APPLICATION_CREDENTIALS"
        value: "/var/run/secret/cloud.google.com/service-account.json"              
    volumeMounts:
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com
      - name: kube-config
        mountPath: /root/.kube     
    resources:
      requests:
        memory: "256Mi"
        cpu: "200m"
      limits:
        memory: "256Mi"
        cpu: "200m"  
  volumes:
  - name: service-account
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: gcpKmsServiceAccount
              path: service-account.json   
  - name: kube-config
    secret:
        secretName: "${pipelineParams.environment}-kube-config"                    
"""
    ) {
        node(POD_LABEL) {
            git url: pipelineParams.repo, branch: pipelineParams.branch, credentialsId: 'git_read'
            stage('Deploy Images') {
                container(name: 'egov-deployer', shell: '/bin/sh') {
                    sh """
                    if [ "${env.LEGACY_DEPLOYER}" = "true" ]; then
                      echo "Legacy deploy mode enabled. Running legacy (go) deploy command"
                      /opt/egov/egov-deployer deploy --helm-dir `pwd`/${pipelineParams.helmDir} -c=${env.CLUSTER_CONFIGS}  -e ${pipelineParams.environment} "${env.IMAGES}"
                      exit \$?
                    fi
    
                    if [ -z "${env.IMAGES}" ]; then
                      echo "No services selected to deploy. Exiting."
                      exit 1
                    fi
    
                    CMD="helmfile -f ${pipelineParams.helmDir}/deigit-helmfile -e ${pipelineParams.environment}"
    
                    if [ "${env.CLUSTER_CONFIGS}" = "true" ]; then
                      CMD="\$CMD --selector target=./configmaps"
                    fi
    
                    if [ "${env.IMAGES}" = "ALL" ]; then
                      CMD="\$CMD apply"
                      echo "Deploying all services via: \$CMD"
                      eval "\$CMD"
                      exit \$?
                    fi
    
                    echo "Deploying below services:"
                    IFS=',' read -ra IMAGE_LIST <<< "${env.IMAGES}"
                    for entry in "\${IMAGE_LIST[@]}"; do
                      if [ "\$entry" = "ALL" ]; then
                        continue
                      fi
    
                      if [[ "\$entry" == *:* ]]; then
                        svc=\${entry%%:*}
                        tag=\${entry##*:}
                        echo "service: \$svc --> image: \$svc:\$tag"
                        CMD="\$CMD --selector target=./\$svc --set \$svc.image.tag=\$tag"
                      else
                        echo "service: \$entry"
                        CMD="\$CMD --selector target=./\$entry"
                      fi
                    done
    
                    CMD="\$CMD apply"
                    echo "Executing: \$CMD"
                    eval "\$CMD"
                """
                }
            }
        }
    }
}
