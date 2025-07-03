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
                    detect_cloud_provider() {
                      if curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/project/project-id >/dev/null 2>&1; then
                        echo "gcp"
                      elif curl -s -H "Accept: application/json" http://169.254.169.254/latest/dynamic/instance-identity/document >/dev/null 2>&1; then
                        echo "aws"
                      elif curl -s -H Metadata:true "http://169.254.169.254/metadata/instance?api-version=2021-02-01" >/dev/null 2>&1; then
                        echo "azure"
                      else
                        echo "unknown"
                      fi
                    }
                    CLOUD_PROVIDER=\$(detect_cloud_provider)
                    if [ "$CLOUD_PROVIDER" = "gcp" ] && [ -f "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
                      gcloud auth activate-service-account --key-file="$GOOGLE_APPLICATION_CREDENTIALS"
                    fi
                    gcloud config list
                    if [ "${env.LEGACY_DEPLOYER}" = "true" ]; then
                      echo "Legacy deploy mode enabled. Running legacy (go) deploy command"
                      /opt/egov/egov-deployer deploy --helm-dir `pwd`/${pipelineParams.helmDir} -c=${env.CLUSTER_CONFIGS}  -e ${pipelineParams.environment} "${env.IMAGES}"
                      exit \$?
                    fi
    
                    if [ -z "${env.IMAGES}" ]; then
                      echo "No services selected to deploy. Exiting."
                      exit 1
                    fi
    
                    CMD="helmfile -f ${pipelineParams.helmDir}/digit-helmfile.yaml -e ${pipelineParams.environment}"
    
                    if [ "${env.CLUSTER_CONFIGS}" = "true" ]; then
                      CMD="\$CMD --selector target=./configmaps"
                    fi
    
                    if [ "${env.IMAGES}" = "ALL" ]; then
                      CMD="\$CMD template"
                      echo "Deploying all services via: \$CMD"
                      eval "\$CMD"
                      exit \$?
                    fi
    
                    echo "Deploying below services:"
                    echo "${env.IMAGES}" | tr ',' '\\n' | while read -r entry; do
                      if [ "\$entry" = "ALL" ]; then
                        continue
                      fi
                    
                      if echo "\$entry" | grep -q ':'; then
                        svc=\$(echo "\$entry" | cut -d: -f1)
                        tag=\$(echo "\$entry" | cut -d: -f2)
                        echo "service: \$svc --> image: \$svc:\$tag"
                        CMD="\$CMD --selector target=./\$svc --set \$svc.image.tag=\$tag"
                      else
                        echo "service: \$entry"
                        CMD="\$CMD --selector target=./\$entry"
                      fi
                    done
    
                    CMD="\$CMD template"
                    echo "Executing: \$CMD"
                    eval "\$CMD"
                """
                }
            }
        }
    }
}
