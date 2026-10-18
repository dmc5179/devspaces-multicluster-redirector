# TODO — Argo CD / GitOps enablement

Tracks the work to make this repo deployable by the OpenShift GitOps operator (Argo CD).

**Branch:** `gitops-argocd-restructure`

**Scope decision:** Argo CD only. RHACM `Policy` is deferred — the multicluster
redirector is a singleton on one host cluster, so fleet governance buys nothing at
N=1. See [Scalability limits and multi-cluster deployments](https://access.redhat.com/documentation/en-us/red_hat_openshift_dev_spaces/3.29/plan-con_running_at_scale)
(Dev Spaces 3.29): *"A critical requirement for the multicluster redirector is that
all users are provisioned to the host cluster where the redirector is deployed."*

RHACM remains the right tool for fanning **Dev Spaces itself** out to the spoke
clusters — that is separate work, tracked in §17 below.

## Status

| # | Task | Status |
|---|------|--------|
| 1 | Create working branch `gitops-argocd-restructure` | done |
| 2 | Restructure `openshift/` into `base/` + `overlays/` | done |
| 3 | Remove committed oauth-proxy session secret | done |
| 4 | Fix hardcoded `namespace: redirector` in the ClusterRoleBinding | done |
| 5 | Handle namespace creation declaratively | done |
| 6 | Parameterize Route host + ServiceAccount OAuth redirect URI | done |
| 7 | Pin the application container image | done |
| 8 | Add probes and resource requests/limits | done |
| 9 | Move `group-mapping.json` out of the base into the overlay | done |
| 10 | Add Argo CD `AppProject` | done |
| 11 | Add Argo CD `Application` | done |
| 12 | Document Argo CD RBAC + secret bootstrap | done |
| 13 | Validate renders with `kubectl kustomize` and server-side dry run | done |
| 14 | Update `README.md` and `CLAUDE.md` for the new layout | done |
| 15 | Commit on branch | done |
| 16 | Add `quarkus-smallrye-health` for real readiness probes | todo (follow-up) |
| 17 | RHACM `Policy` to deploy Dev Spaces to spoke clusters | todo (separate effort) |

## Detail

### 1. Create working branch
Branch off `main` so the restructure can be reviewed as a unit.

### 2. Restructure `openshift/` into `base/` + `overlays/`
Standard Kustomize layout. The base holds cluster-agnostic manifests; each overlay
pins the four things that genuinely differ per cluster (Route host, OAuth redirect
URI, image, group mapping).

**Breaking change:** `kubectl apply -k openshift` becomes
`kubectl apply -k openshift/overlays/<name>`.

### 3. Remove committed oauth-proxy session secret
`openshift/secrets.yaml:7` committed `c2Vzc2lvbl9zZWNyZXQ=`, which decodes to the
literal string `session_secret`. A publicly known cookie secret means forgeable
oauth-proxy sessions. Deleted from the repo; generated out-of-band instead. Also
avoids Argo CD `selfHeal` reverting a rotated secret back to the known value.

### 4. Fix hardcoded `namespace: redirector` in the ClusterRoleBinding
`openshift/clusterrolebinding.yaml:12` pinned the subject namespace. Deploying to
any other namespace broke RBAC silently while Argo CD still reported Synced/Healthy.
Fixed by letting the Kustomize `namespace:` transformer rewrite the ServiceAccount
subject namespace from a single declaration in the overlay.

### 5. Handle namespace creation declaratively
Base carries no `Namespace`. The Argo CD `Application` uses
`syncOptions: [CreateNamespace=true]`; `kubectl` users get the namespace from the
overlay's `namespace:` field plus an explicit `oc new-project`.

### 6. Parameterize Route host + ServiceAccount OAuth redirect URI
These two must agree or the OAuth flow fails, and neither can be known from the base.
The overlay sets `Route.spec.host` explicitly and derives the
`serviceaccounts.openshift.io/oauth-redirecturi.primary` annotation from it.

### 7. Pin the application container image
`:latest` is non-deterministic: Argo CD reports Synced while the running image
drifts. The overlay pins the tag via `images:`; production should pin by digest.
The oauth-proxy sidecar was already digest-pinned.

### 8. Add probes and resource requests/limits
Without probes, Argo CD health degrades to "replicas available". Without requests,
quota-enforced clusters may reject the pods.

Note: the app has no `quarkus-smallrye-health` extension, so there is no
`/q/health/ready`. Probes therefore only prove the HTTP listener and servlet chain
are up — `Redirect404Filter` turns every unmatched path into a 302, which an
`httpGet` probe also counts as success. See §16.

### 9. Move `group-mapping.json` out of the base into the overlay
The base ConfigMap carried real environment URLs. Kept as a plain `ConfigMap`
resource, **not** a `configMapGenerator`: the generator appends a content hash,
which changes the volume reference and rolls the pod on every mapping edit —
defeating the live-reload design in `GroupMappingService`.

Consequence to accept deliberately: with `selfHeal: true`, Git becomes the only way
to change mappings. Add the ConfigMap to `ignoreDifferences` if out-of-band edits
must keep working.

### 10–12. Argo CD wiring
`AppProject` with an explicit `clusterResourceWhitelist` (the app ships a ClusterRole
and ClusterRoleBinding), the `Application`, and a README covering the session-secret
bootstrap and the extra controller RBAC needed by non-default Argo CD instances.

### 13. Validate
`kubectl kustomize` on base and overlay, plus `kubectl apply --dry-run`.

### 14. Update docs
`README.md` and `CLAUDE.md` both document the old flat layout and the old
`kubectl apply -k openshift` command.

### 16. Add `quarkus-smallrye-health` (follow-up)
Adds `/q/health/live` and `/q/health/ready` so the probes in §8 assert something
meaningful. Application change, so kept out of this deployment-focused branch.

### 17. RHACM `Policy` for Dev Spaces on spokes (separate effort)
Wrap the `Subscription` + `CheCluster` from the Dev Spaces install docs in a
`Policy`, bound with `Placement` + `PlacementBinding` (note: `PlacementRule` is
deprecated). Use the RHACM Policy Generator rather than hand-writing
`object-templates`.
