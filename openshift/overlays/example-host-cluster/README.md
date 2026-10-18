# Example overlay

Copy this directory per host cluster and change the four marked values:

| # | Value | File |
|---|-------|------|
| 1 | `Route.spec.host` | `route-host.yaml` |
| 2 | OAuth redirect URI — must equal `https://<host>/oauth/callback` | `serviceaccount-oauth.yaml` |
| 3 | Application image tag or digest | `kustomization.yaml` (`images:`) |
| 4 | Group → Dev Spaces URL mapping | `configmap-group-mapping.yaml` |

Values 1 and 2 must agree exactly or the OAuth callback fails.

If you deploy to a namespace other than `redirector`, change the `namespace:` field
in `kustomization.yaml` only. Do not add a namespace to the ClusterRoleBinding
subject — see `../../../argocd/README.md` for why.

Render before committing:

```bash
kubectl kustomize openshift/overlays/<your-cluster>
```

Creating the oauth-proxy session Secret is a separate out-of-band step; see
`../../../argocd/README.md`.
