# RHACM: installing Dev Spaces on the spoke clusters

This is the **spoke-side half** of the multicluster setup. It does not deploy the
redirector.

| Component | Where it runs | How it is deployed |
|---|---|---|
| Multicluster redirector | One host cluster | Argo CD — see [`../argocd/README.md`](../argocd/README.md) |
| Dev Spaces instance | Every spoke cluster | RHACM `Policy` — this directory |

The split follows the product architecture. Per
[Scalability limits and multi-cluster deployments](https://access.redhat.com/documentation/en-us/red_hat_openshift_dev_spaces/3.29/plan-con_running_at_scale)
(Dev Spaces 3.29):

> By design, Red Hat OpenShift Dev Spaces is not multi-cluster aware. You can only
> have one instance per cluster.

…and all users must be provisioned on the host cluster, because the host cluster's
OpenShift groups drive the redirector's routing. So the redirector is a singleton
(Argo CD) and Dev Spaces is a fleet workload (RHACM).

## What the policy does

`policy-devspaces.yaml` contains one `Policy` with two templates, plus a
`Placement` and `PlacementBinding`:

1. **`devspaces-operator`** — creates the `openshift-devspaces` namespace and the
   `devspaces` Subscription in `openshift-operators`.
2. **`devspaces-checluster`** — creates the `CheCluster` instance, gated on
   template 1 being `Compliant`.

The wrapped manifests are the ones Red Hat documents for a GitOps install of Dev
Spaces.

### Two things that are easy to get wrong

**Ordering.** The `org.eclipse.che/v2` CRD does not exist until OLM finishes
installing the operator. An ungated CheCluster template would churn `NonCompliant`
on every fresh spoke. Template 2 therefore declares:

```yaml
extraDependencies:
  - apiVersion: policy.open-cluster-management.io/v1
    kind: ConfigurationPolicy
    name: devspaces-operator
    compliance: Compliant
ignorePending: true
```

`ignorePending: true` makes the `Pending` state count as Compliant, so the policy
does not alarm during a normal install window.

**`musthave`, not `mustonlyhave`.** The Dev Spaces operator writes defaults back
into the CheCluster spec. `mustonlyhave` demands an exact match, so the policy and
the operator would overwrite each other indefinitely — the flapping-compliance
failure described in [this solution](https://access.redhat.com/solutions/7012967).

## Deploying

1. Label the spokes that should run Dev Spaces:

   ```bash
   oc label managedcluster <cluster-name> devspaces=true
   ```

2. Ensure the `policies` namespace exists on the hub and is bound to a
   `ManagedClusterSet` covering those clusters.

3. Review the `# CHANGE ME` markers, then apply to the hub:

   ```bash
   oc apply -f rhacm/policy-devspaces.yaml
   ```

4. The policy ships `remediationAction: inform`. Confirm the Governance dashboard
   reports what you expect, then switch to `enforce`:

   ```bash
   oc patch policy devspaces-install -n policies --type=merge \
     -p '{"spec":{"remediationAction":"enforce"}}'
   ```

5. Verify on a spoke:

   ```bash
   oc get subscription devspaces -n openshift-operators
   oc get checluster devspaces -n openshift-devspaces
   ```

## Keeping the group mapping in sync

Once a spoke is running, its Dev Spaces URL has to be added to the redirector's
`group-mapping.json` in the host cluster's overlay. That is currently a manual Git
edit.

Deriving it automatically from `ManagedCluster` state is appealing but unresolved:
the group→cluster assignment encodes a people decision (which team belongs where),
not an infrastructure fact, so it may not be derivable from cluster metadata at all.
Worth designing before building.

## If this grows

For a single policy, hand-written YAML is the clearest and most reviewable form.
Once there are several policies, switch to the
[Policy Generator](https://access.redhat.com/documentation/en-us/red_hat_advanced_cluster_management_for_kubernetes/2.17/html-single/governance/index),
which generates `Policy` / `Placement` / `PlacementBinding` from plain manifests and
keeps those manifests the single source of truth.

Note `PlacementRule` is deprecated; this policy uses `Placement` +
`PlacementBinding`.

## Verification status

The policy has been structurally validated only (YAML parse; template, dependency,
and binding wiring checked). It has **not** been applied to an RHACM hub — schema
validation against the `policy.open-cluster-management.io` and `org.eclipse.che/v2`
CRDs, and the actual install on a spoke, are still outstanding.
