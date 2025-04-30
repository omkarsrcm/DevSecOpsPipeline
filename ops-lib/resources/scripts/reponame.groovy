import jenkins.*
     import jenkins.model.*
     import hudson.*
     import hudson.model.*
     import groovy.json.JsonSlurper
     credentialsId = 'azure-bot'
     def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
  com.cloudbees.plugins.credentials.common.StandardUsernameCredentials.class, Jenkins.instance, null, null ).find{
    it.id == credentialsId}
    def clusterlist = "curl -u ${creds.username}:${creds.password} -X GET https://dev.azure.com/omvexis-Tech/Product_Mgmt/_apis/git/repositories/ops-pipeline-libraries/items?path=/resources/files/cluster.json&download=true&versionDescriptor.versionType=branch&version=master&api-version=6.1-preview.1".execute().text
  def jsonSlurper = new JsonSlurper()
  def result = jsonSlurper.parseText(clusterlist)
  def repo_name = result.cluster.repo_name
  repo_list = repo_name.split('\n').collect{it as String}
  return repo_list