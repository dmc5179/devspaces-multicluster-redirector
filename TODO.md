# TODO — Argo CD / GitOps enablement

**Branch:** `gitops-argocd-restructure` · **PR:** [#1](https://github.com/dmc5179/devspaces-multicluster-redirector/pull/1)

---

## The required path

Deploying the redirector is three steps. Everything below this section is
optional, a pre-existing defect, or a different system.

1. **Create the session secret** (out-of-band — deliberately not in Git):

   ```bash
   oc create secret generic devspaces-multicluster-redirector-session-secret \
     -n redirector --from-literal=session_secret="$(openssl rand -base64 32)"
   ```

2. **Set two values in your overlay** — the Route host and the matching OAuth
   redirect URI. They must agree exactly. Optionally also the image tag and the
   group mapping.

3. **Apply:**

   ```bash
   oc apply -f argocd/appproject.yaml -f argocd/application-redirector.yaml
   ```

   Or without Argo CD: `kubectl apply -k openshift/overlays/<cluster>`

That's it. The app is ~7 resources: a Deployment with an oauth-proxy sidecar, a
Service, a Route, a ConfigMap, and a ServiceAccount with its cluster-scoped RBAC
pair.

### Why there is any complexity at all

The redirect itself is trivial. The complexity is entirely in **authentication and
group lookup** — that is what brings the oauth-proxy sidecar, the cluster-scoped
RBAC, the session secret, and the OAuth callback URL. Three things are genuinely
irreducible:

- **Route host ↔ OAuth redirect URI.** The ServiceAccount annotation must match the
  Route host exactly, and a generated host is not known until the Route exists. Git
  cannot resolve that, so the hostname gets pinned per cluster.
- **The session secret cannot live in Git.** Any GitOps setup needs an out-of-band
  step or a secrets operator.
- **Cluster-scoped RBAC forces an AppProject whitelist** — only if you use a scoped
  Argo CD project. The default `openshift-gitops` instance runs as cluster-admin and
  does not care.

---

## Done in this branch

| Area | What | Why it was needed |
|---|---|---|
| Argo CD wiring | `AppProject`, `Application`, deployment guide | The actual ask — 3 files |
| Multi-cluster | base + overlay split; Route host, OAuth URI, image, group mapping parameterized | Flat manifests could only ever target one cluster |
| Security | Removed the committed session secret | **Pre-existing.** `c2Vzc2lvbl9zZWNyZXQ=` decodes to the literal `session_secret` — a known cookie secret means forgeable sessions |
| Correctness | Removed the hardcoded namespace from the ClusterRoleBinding subject | **Pre-existing.** Kustomize only rewrites a subject namespace that is absent or `default`, so the literal survived the overlay and bound to the wrong ServiceAccount — with Argo CD still reporting Synced/Healthy |
| Hygiene | Pinned the image; added probes, resource limits, and real health checks | `:latest` makes the deployed state unreproducible; there were no probes at all |
| Separate system | RHACM `Policy` installing Dev Spaces on the spokes (`rhacm/`) | Not the redirector — the fleet-side half |

Detail on the two subtler ones is in `argocd/README.md` and `rhacm/README.md`.

---

## Open — no cluster required

### 1. CI does not run on pull requests
`.github/workflows/build-and-push-native-image.yaml` triggers only on
`push: branches: [main]`. Nothing validates a PR; problems surface only after
merge. Add a `pull_request` trigger that runs `mvn test` and
`kubectl kustomize` over each overlay.

### 2. The "native image" build is not native
**Pre-existing, and the more serious of the two.** The workflow runs
`mvn package -Pnative ...`, but `pom.xml` has no `<profiles>` section and no parent
POM. Verified with `mvn help:active-profiles -Pnative`:

```
[WARNING] The requested profile "native" could not be activated because it does not exist.
The following profiles are active:
    (none)
```

Maven warns and continues, so the job builds and pushes a **JVM image** despite its
name. The published `quay.io/redhat-developer/devspaces-multicluster-redirector:latest`
has presumably never been a native binary.

Fix is either adding the standard Quarkus `native` profile or switching the workflow
to `-Dnative` (the property form, which Quarkus 3.x honours directly — and which is
what `README.md` and `CLAUDE.md` already document). Decide which, since the two
docs and the CI currently disagree.

Knock-on: the resource limits I set (256Mi request / 512Mi limit) were sized for a
JVM, which turns out to be correct for what actually ships. Revisit if the build is
made genuinely native — a native binary needs far less.

### 3. Fill in the real values
The four `# CHANGE ME` markers in the overlay and the
`repoURL` / `targetRevision` / `path` in both `argocd/` files. `targetRevision`
currently points at this branch and should move to a tag or SHA. Needs your
hostnames and group names, not cluster access.

---

## Open — needs a cluster

### 4. Server-side schema validation
Everything so far is rendered and parsed, never schema-checked. One command once
connected:

```bash
kubectl kustomize openshift/overlays/<cluster> | oc apply --dry-run=server -f -
oc apply --dry-run=server -f argocd/ -f rhacm/
```

Catches CRD and field errors in the Route, the Argo CD resources, and the RHACM
policy in seconds.

### 5. End-to-end OAuth
The highest-risk unknown, and unverifiable any other way: does the callback
actually complete, and does a user land on the right Dev Spaces instance for their
groups. Worth doing with a manual `kubectl apply -k` first, before handing control
to Argo CD — debugging a redirect-URI mismatch is much easier without a controller
reconciling underneath you.

### 6. Confirm the image pulls
`registry.redhat.io/devspaces/multicluster-redirector-rhel9:3.27` needs a pull
secret and an entitled cluster. Also confirms the tag exists.

### 7. Readiness behaves correctly in-cluster
Health checks were only verified in the DOWN path (no cluster under test). Confirm
they report UP when the ConfigMap is mounted and the groups API is reachable, and
that the probe latency is sane against a real API.

### 8. Validate the RHACM policy on a hub
Needs an RHACM hub with managed clusters — a different environment from a plain
OCP cluster. Structurally validated only so far.

---

## Open — design questions

### 9. Keeping the group mapping in sync
Adding a spoke still means hand-editing `group-mapping.json`. Deriving it from
`ManagedCluster` state is appealing but may not be possible: the group→cluster
assignment encodes which team belongs where, which is a people decision, not an
infrastructure fact. Decide before anyone builds it.

### 10. Readiness gating on an external API
`OpenShiftApiHealthCheck` is annotated `@Readiness`, so an OpenShift API outage
takes the pod out of the Service rather than serving a degraded page. Intentional —
the app has no useful behaviour without group data — but with `replicas: 1` that
makes an API blip a user-visible 503. Drop the annotation to make it observational
only.
