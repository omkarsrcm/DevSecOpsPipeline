# 🧪 Omvexis CI/CD Pipeline: Jenkins Service Builder & Deployer

This repository contains **two Jenkins pipelines** that implement a full CI/CD lifecycle for Dockerized microservices within the **Omvexis** platform. Together, these pipelines:

- Clone, build, scan, and test services (`cs_service_builder`)
- Deploy them to Kubernetes clusters using Helm charts (`cs_service_deployer`)

---

Demo Video :- https://drive.google.com/file/d/1asMBvTXnhAYdujrXEEPcBtQ60jioBHHK/view?usp=sharing

## 🔗 Overview

| Pipeline            | Purpose                                                                 |
|---------------------|-------------------------------------------------------------------------|
| `cs_service_builder`  | CI: Code checkout, unit testing, security analysis, Docker build, and optional deployment trigger |
| `cs_service_deployer` | CD: Pull built Docker image, generate app-specific config, Helm chart packaging, deploy to cluster |

---

## 1️⃣ Jenkins Pipeline: `cs_service_builder`

This pipeline runs CI tasks and optionally triggers deployment.

### 🧷 Parameters

| Name              | Description |
|-------------------|-------------|
| `Docker_Build_Type` | Choose `federal` (IronBank hardened image with `Dockerfile.ironbank`) or `commercial` (default Dockerfile) |
| `BranchName`      | Name of the Git branch to clone from Azure DevOps |
| `Deployment`      | Boolean switch. If `true`, deploys after successful image build |
| `Script_Name`     | Comma-separated list of deployment scripts to run (`migrate.py,init.py`) |
| `Repo_Name`       | Microservice repo to build (`hfnlife-ui`, `hfnlife_orders`, etc.) |
| `Cluster_Type`    | Cluster where the build or deployment is targeted (`hfnlife_dev`, `hfnlife_prd`) |

---

### ⚙️ Stages Explained

#### 🛂 1. Trigger Validation
Captures the user who triggered the build via the `BuildUser` plugin and logs it.

#### 🧰 2. Initialize Pipeline Config (Parallel)
- Loads cluster-specific registry credentials and sets image tag format (`1.0.0-yyyyMMdd`).
- Chooses Dockerfile (`Dockerfile` or `Dockerfile.ironbank`) based on build type.
- Authenticates to **Snyk** using stored Jenkins credentials (`snyk_api_token`).

#### 📥 3. Clone and Checkout Repository
- Authenticates with Azure DevOps using `jenkins_ops_lib` credentials.
- Performs a **shallow clone** (`--depth=1`) of the selected repo and branch.
- Captures the latest Git commit hash (`CommitId`) for tagging.

#### 🔍 4. Static Analysis (Parallel)
- **Unit Tests**: Creates virtualenv (`venv1`), installs `requirements_unit_test.txt`, and runs `pytest`.
- **SCA (Open Source Scan)**:
  - Creates virtualenv (`venv2`)
  - Installs `requirements.txt`
  - Runs `snyk test` and generates `snyk-sca-report.html`
- **SAST (Code Scan)**:
  - Executes `snyk code test`, generates `snyk-sast-report.html`
  - Fails the build if issues are found

All reports are archived for traceability.

#### 📑 5. Template Config Verification
- Ensures `template.conf` exists
- Encodes it with base64 and stores in `Encoded_Conf` for labeling Docker image

#### 🏗️ 6. Initialize Build Image (Parallel)
- Logs into the appropriate Docker registry
- Builds Docker image via shared library method `dockerbuild(...)`, with custom labels:
  - `cs_gitcommit` = Git commit SHA
  - `cs_template` = base64 of `template.conf`
- Pushes the image to the registry

#### 🔬 7. Image Security Scan
- Uses **Trivy** to scan the Docker image for vulnerabilities
- Fails the pipeline if any issues are detected

#### 🚀 8. Docker Image Deployer (Conditional)
- If `Deployment` is enabled, this triggers the `cs_service_deployer` job with parameters like tag, script names, cluster, and repo name.

#### 📣 9. Post Actions
- Sends failure notifications via Office365 connector webhook (`sendBuilderNotificationURL()`)

---

## 2️⃣ Jenkins Pipeline: `cs_service_deployer`

This pipeline handles the **deployment** of previously built Docker images using **Helm charts**.

### 🧷 Parameters

| Name              | Description |
|-------------------|-------------|
| `Docker_Build_Type` | `federal` or `commercial` |
| `Tag_No`          | Docker image tag (e.g. `1.0.0-20240429`) |
| `Cluster_Type`    | Cluster name (`hfnlife_dev`, `hfnlife_prd`) |
| `Script_Name`     | Optional: comma-separated deployment scripts |
| `Service_Name`    | Microservice to deploy (`hfnlife_orders`, etc.) |

---

### ⚙️ Stages Explained

#### 👤 1. User Validation *(only for some environments)*
- Validates the triggering user against a YAML file stored in Azure DevOps (`AuthorizedUserList.yaml`)
- Uses `yq` to extract permissions

#### 🧰 2. Pipeline Config (Parallel)
- Reads `cluster.json` for Docker registry credentials
- Authenticates to registry and pulls **image label** using shared lib `csDockerImageLabelFetch(...)`
- Extracts and decodes base64 `template.conf` label into plain config

#### 🧾 3. Create App Config File
- Downloads `omvexis_config.yaml` from `hfnlife_cumulus_config` Git repo
- Converts it to JSON using `yaml-to-json.py`
- Produces final config file: `${Service_Name}.conf`

#### 🧰 4. Helm Chart Creation
- Clones central Helm chart repo (`hfnlife_k8s`)
- Updates:
  - `Chart.yaml` and `values.yaml` with `version`, `tag`, and `image.repository`
- Embeds the generated `${Service_Name}.conf` into Helm configMap
- Packages Helm chart and pushes to Docker Hub OCI registry

#### 🚢 5. Perform Deployment
- Executes Helm install/upgrade via `csDeployer(...)` function with full chart URL and target cluster

---

## 🔧 Tools & Technologies

- **Jenkins on Kubernetes** using dynamic agents
- **Snyk** for:
  - SAST (Static App Sec Testing)
  - SCA (Software Composition Analysis)
- **Trivy** for Docker image vulnerability scanning
- **Helm** for Kubernetes deployments
- **Python & Jinja2** for config templating
- **Azure DevOps Git** for source and config repositories

---

## 🔐 Required Jenkins Credentials

| ID                  | Purpose                               |
|---------------------|---------------------------------------|
| `jenkins_ops_lib`   | Azure DevOps Git access               |
| `snyk_api_token`    | Snyk CLI auth                         |
| `Registry_Cred`     | Docker registry push/pull             |
| `helmlogin`         | Helm registry login (OCI support)     |

---

## 📎 Supporting Repositories

- 🔧 `hfnlife_k8s`: Helm charts for all services
- ⚙️ `hfnlife_cumulus_config`: YAML config files per environment

---

## 👥 Maintainers

> 📫 **Omkar Kulkarni**  
> ✉️ `Omkar Kulkarni`  

---

