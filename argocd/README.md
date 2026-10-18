# Deploying the redirector with OpenShift GitOps (Argo CD)

## Topology

The redirector is a **singleton on one host cluster**, not a fleet workload. Per
[Scalability limits and multi-cluster deployments](https://access.redhat.com/documentation/en-us/red_hat_openshift_dev_spaces/3.29/plan-con_running_at_scale)
(Dev Spaces 3.29):

> A critical requirement for the multicluster redirector is that all users are
> provisioned to the host cluster where the redirector is deployed. Users
> authenticate through the OAuth flow of this cluster, even if they never run
> workloads there. The host cluster's OpenShift Container Platform groups determine
> the routing logic.

That is why this is a plain Argo CD `Application` rather than an RHACM `Policy` —
there is nothing to fan out at N=1. RHACM is still the right tool for installing
**Dev Spaces itself** on the spoke clusters; see `TODO.md` section 17.

Because the ClusterRole and ClusterRoleBinding names are cluster-scoped singletons,
only one redirector can be installed per cluster. This matches the architecture.

## Prerequisites

1. OpenShift GitOps operator installed, with the default Argo CD instance in
   `openshift-gitops`.
2. The target namespace exists, or the `Application` creates it via
   `CreateNamespace=true`.
3. **The oauth-proxy session Secret exists.** See below.

## Step 1 — create the session Secret out-of-band

The Secret is intentionally not in Git. The upstream sample committed
`c2Vzc2lvbl9zZWNyZXQ=`, which decodes to the literal string `session_secret`; a
publicly known cookie secret means forgeable oauth-proxy sessions. Keeping it out
of Git also stops `selfHeal` from reverting a rotated value.

```bash
oc create namespace redirector --dry-run=client -o yaml | oc apply -f -

oc create secret generic devspaces-multicluster-redirector-session-secret \
  -n redirector \
  --from-literal=session_secret="$(openssl rand -base64 32)"
```

The pods will not start until this exists — a loud, correct failure.

For production, manage it with the External Secrets Operator, Sealed Secrets, or
SOPS instead of a manual `oc create`.

## Step 2 — prepare an overlay

Copy `openshift/overlays/example-host-cluster/` and change the four marked values:

| # | Value | File |
|---|-------|------|
| 1 | `Route.spec.host` | `route-host.yaml` |
| 2 | OAuth redirect URI (must equal `https://<host>/oauth/callback`) | `serviceaccount-oauth.yaml` |
| 3 | Application image tag or digest | `kustomization.yaml` (`images:`) |
| 4 | Group → Dev Spaces URL mapping | `configmap-group-mapping.yaml` |

Also set `namespace:` in the overlay's `kustomization.yaml` if you are not using
`redirector`. That one field also rewrites the ServiceAccount subject inside the
ClusterRoleBinding — do not hardcode a namespace there. (Kustomize only rewrites a
subject namespace that is absent or `default`; a literal value would survive the
transformer and bind to the wrong ServiceAccount while Argo CD still reported
Synced and Healthy.)

Render it before committing:

```bash
kubectl kustomize openshift/overlays/<your-cluster>
```

## Step 3 — apply the AppProject and Application

Edit `repoURL`, `targetRevision`, and `path` in both files first.

```bash
oc apply -f argocd/appproject.yaml
oc apply -f argocd/application-redirector.yaml
```

## Step 4 — verify

```bash
oc get application devspaces-multicluster-redirector -n openshift-gitops
oc get pods -n redirector
oc get route devspaces-multicluster-redirector -n redirector
```

Then open the Route host in a browser and confirm the OAuth login completes and you
land on a Dev Spaces instance matching your group membership.

## Argo CD controller RBAC

The default `openshift-gitops` Argo CD instance runs with `cluster-admin`, so no
extra RBAC is needed.

If you use a namespace-scoped or custom Argo CD instance, grant its application
controller permission to manage the cluster-scoped resources this app ships. This
follows the pattern Red Hat documents for Dev Spaces itself in
[Deploy with GitOps in an air-gapped environment](https://access.redhat.com/documentation/en-us/red_hat_openshift_dev_spaces/3.29/install-proc_deploying_devspaces_using_gitops_in_a_restricted_environment):

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: openshift-gitops-redirector-installer
rules:
  - apiGroups: [""]
    resources: ["namespaces"]
    verbs: ["get", "list", "create", "update", "patch"]
  - apiGroups: ["rbac.authorization.k8s.io"]
    resources: ["clusterroles", "clusterrolebindings"]
    verbs: ["get", "list", "create", "update", "patch", "delete"]
  - apiGroups: ["route.openshift.io"]
    resources: ["routes"]
    verbs: ["get", "list", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: openshift-gitops-redirector-installer
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: openshift-gitops-redirector-installer
subjects:
  - kind: ServiceAccount
    name: openshift-gitops-argocd-application-controller
    namespace: openshift-gitops
```

Granting `clusterroles` write access requires the granting identity to already hold
those permissions, or the `escalate` verb.

## Group mapping and selfHeal

With `selfHeal: true`, Git is the only way to change group mappings — out-of-band
`oc edit` changes are reverted within minutes. The app's live-reload still works
(no pod restart on change), but the change has to arrive through Git.

To keep ad-hoc edits working, uncomment the `ignoreDifferences` block in
`application-redirector.yaml`.

The ConfigMap is deliberately a plain resource rather than a `configMapGenerator`:
the generator appends a content hash to the name, which changes the volume
reference and rolls the pod on every edit, defeating the live-reload in
`GroupMappingService`.
