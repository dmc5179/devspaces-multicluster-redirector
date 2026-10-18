# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Quarkus-based intelligent routing service that redirects users to their designated Red Hat OpenShift Dev Spaces instance based on OpenShift group membership. The application runs with an OAuth Proxy sidecar for authentication and uses the OpenShift/Kubernetes API to query group membership.

## Build and Development Commands

### Development Mode
```bash
mvn quarkus:dev
```
Starts the application in dev mode with live reload on `http://localhost:8080`

### Build
```bash
mvn clean package
```
Creates a runnable JAR at `target/quarkus-app/quarkus-run.jar`

### Run Tests
```bash
mvn test
```

### Run Single Test
```bash
mvn test -Dtest=ClassName
# Example: mvn test -Dtest=NotFoundRedirectFilterTest
```

### Build Native Executable
```bash
mvn package -Dnative
# Or with container build:
mvn package -Dnative -Dquarkus.native.container-build=true
```

### Build Container Image
```bash
mvn package -Dquarkus.container-image.build=true
```

### Deploy to OpenShift
```bash
# Create the oauth-proxy session secret first — it is NOT in Git (see argocd/README.md)
oc create secret generic devspaces-multicluster-redirector-session-secret \
  -n redirector --from-literal=session_secret="$(openssl rand -base64 32)"

kubectl apply -k openshift/overlays/example-host-cluster
```

Manifests use a Kustomize base + overlay layout. The base is not directly
applicable — it has no Route host and no OAuth redirect URI. Always apply an
overlay. For Argo CD, see `argocd/README.md`.

**Container Images:**
- **Development (Upstream):** `quay.io/redhat-developer/devspaces-multicluster-redirector:latest`
- **Production (Downstream):** `registry.redhat.io/devspaces/multicluster-redirector-rhel9:latest`
  - Red Hat certified image from [Red Hat Ecosystem Catalog](https://catalog.redhat.com/en/software/containers/devspaces/multicluster-redirector-rhel9/69a1b8d94d1e7c99baa33970)
  - Use specific version tags (e.g., `3.27`) or digest in production
  - Requires authentication: `podman login registry.redhat.io`

To use the production image, set it in the overlay's `images:` field (see
`openshift/overlays/example-host-cluster/kustomization.yaml`) or patch:
```bash
oc set image deployment/devspaces-multicluster-redirector \
  devspaces-multicluster-redirector=registry.redhat.io/devspaces/multicluster-redirector-rhel9:latest
```

### Clean Up OpenShift Resources
```bash
kubectl delete -k openshift/overlays/example-host-cluster
```

## Architecture Overview

### Request Flow
1. User hits the redirector URL (Route → Service:8443 → OAuth Proxy)
2. OAuth Proxy authenticates via OpenShift OAuth
3. OAuth Proxy injects headers (`X-Forwarded-User`, `X-Forwarded-Groups`) and forwards to Quarkus app (port 8080)
4. Quarkus app queries OpenShift API for user's group memberships
5. Application loads group-to-URL mappings from ConfigMap at `/etc/config/group-mapping.json`
6. User is redirected to the appropriate Dev Spaces instance
7. All 404 errors redirect to home page via `Redirect404Filter`

### Key Components

**GroupMappingService** (`GroupMappingService.java`)
- Reads ConfigMap from `/etc/config/group-mapping.json` on every request
- Handles Kubernetes symlink-based ConfigMap updates with retry logic
- Tracks file modification times to detect updates
- Supports comma-separated group keys with AND logic (e.g. `"team-alpha, team-beta"` requires membership in both groups)
- `getMatchingMappings(List<String> userGroups)` — returns all entries whose key matches via AND logic
- `matchesAllGroups(String groupKey, Collection<String> userGroups)` — static helper; package-private for unit testing

**OpenShiftGroupService** (`OpenShiftGroupService.java`)
- Initializes Fabric8 OpenShiftClient using service account token
- Queries OpenShift API for groups (`user.openshift.io/groups` resources)
- Methods: `getAllGroups()`, `getUserGroups(userName)`, `userBelongsToGroup(groupName, userName)`
- Requires ClusterRole with `get` and `list` permissions on groups

**Redirect404Filter** (`Redirect404Filter.java`)
- Servlet filter that intercepts all 404 responses
- Rewrites 404s to 302 redirects to `/` (home page)
- Uses `HttpServletResponseWrapper` to intercept `sendError()` and `setStatus()` calls
- Applies to all dispatcher types (REQUEST, FORWARD, INCLUDE, ERROR, ASYNC)

**Undertow Handlers** (`META-INF/undertow-handlers.conf`)
- URL rewriting rules for SPA-style routing
- Maps paths like `/f`, `/swagger`, `/dashboard` to `/index.html`

### OpenShift Deployment Architecture

**Manifest layout (Kustomize base + overlays):**
```
openshift/base/                          # cluster-agnostic; not directly applicable
openshift/overlays/<cluster>/            # Route host, OAuth redirect URI, image, group mapping
argocd/appproject.yaml                   # AppProject (whitelists the cluster-scoped RBAC)
argocd/application-redirector.yaml       # Argo CD Application
argocd/README.md                         # GitOps deployment guide
```

The redirector is a **singleton on one host cluster** — all users must be
provisioned there because the host cluster's OpenShift groups drive the routing
([Dev Spaces 3.29 docs](https://access.redhat.com/documentation/en-us/red_hat_openshift_dev_spaces/3.29/plan-con_running_at_scale)).
The ClusterRole/ClusterRoleBinding names are cluster-scoped singletons, so only one
instance per cluster is possible, which matches that architecture.

Two values must always agree: `Route.spec.host` and the ServiceAccount's
`serviceaccounts.openshift.io/oauth-redirecturi.primary` annotation. Both live in
the overlay.

Never put a literal namespace on the ClusterRoleBinding's ServiceAccount subject.
Kustomize only rewrites a subject namespace that is absent or `default`, so a
literal value survives the overlay's `namespace:` field and binds to the wrong
ServiceAccount — while Argo CD still reports Synced and Healthy.

**Two-container Pod:**
1. **devspaces-multicluster-redirector** (port 8080) - Quarkus application
2. **oauth-proxy** (port 8443) - OpenShift OAuth Proxy sidecar

**ConfigMap Mount:**
- ConfigMap `devspaces-openshift-group-mapping` mounted at `/etc/config`
- Contains `group-mapping.json` with group → URL mappings
- Kubernetes updates ConfigMaps via symlinks, which GroupMappingService handles

**RBAC:**
- ServiceAccount: `devspaces-multicluster-redirector`
- ClusterRole grants access to `user.openshift.io/groups` resources
- Service account token auto-mounted for OpenShift API authentication

**OAuth Proxy Configuration:**
- Provider: `openshift`
- Upstream: `http://localhost:8080`
- Passes user headers to application
- Session secret from Secret `devspaces-multicluster-redirector-session-secret`
- That Secret is **not in Git** and is not managed by Argo CD. Create it out-of-band
  with a random value; pods will not start until it exists. See `argocd/README.md`.

## Important Implementation Details

### ConfigMap Hot Reload Mechanism
The `GroupMappingService` reads the ConfigMap file on every request to ensure fresh data:
- Resolves symlinks with `Path.toRealPath()` (Kubernetes uses symlinks for atomic updates)
- Tracks `lastKnownModificationTime` to detect changes
- Waits 100ms when modification time changes to let Kubernetes complete the update
- Re-resolves symlink after wait to catch final state
- Reads file as raw bytes to bypass caching

### Cache Control
All API responses include headers to prevent caching:
- `Cache-Control: no-cache, no-store, must-revalidate`
- `Pragma: no-cache`
- `Expires: 0`

This ensures users always get fresh group mappings and redirect decisions.

### OpenShift Client Initialization
The Fabric8 OpenShiftClient auto-discovers configuration:
- Uses service account token from `/var/run/secrets/kubernetes.io/serviceaccount/token`
- Detects cluster API server URL from environment
- No manual configuration needed when running in-cluster

## Technology Stack

- **Framework:** Quarkus 3.39.5
- **Java Version:** 17
- **Build Tool:** Maven
- **Kubernetes Client:** Fabric8 OpenShift Client 6.13.4
- **REST Framework:** Jakarta REST (JAX-RS) with Quarkus REST
- **Servlet:** Quarkus Undertow (for filter support)
- **JSON:** Jackson (quarkus-rest-jackson)
- **Testing:** JUnit 5, REST Assured

## Configuration Files

**application.properties:**
- `quarkus.http.port=8080` - HTTP port
- `quarkus.container-image.registry=quay.io` - Container registry
- `quarkus.container-image.name=redhat-developer/devspaces-multicluster-redirector` - Image name

**openshift/overlays/\<cluster\>/configmap-group-mapping.yaml:**
- Edit `group-mapping.json` to add/modify group → URL mappings
- Keys may be a single group name or a comma-separated list; comma-separated keys require the user to belong to **all** listed groups (AND logic)
- Changes are auto-detected without pod restart
- The base ConfigMap (`openshift/base/configmap.yaml`) is intentionally empty — mappings are environment-specific and belong in the overlay
- Kept as a plain ConfigMap, **not** a `configMapGenerator`: the generator's name hash changes the volume reference and rolls the pod on every edit, defeating the live-reload design
- Under Argo CD with `selfHeal: true`, Git is the only way to change mappings unless the ConfigMap is added to `ignoreDifferences`

## REST API Endpoints

- `GET /api/group-mapping` - Returns all group-to-URL mappings (JSON)
- `GET /api/groups` - Lists all OpenShift groups
- `GET /api/user` - Returns user info from OAuth headers including matched Dev Spaces URLs

## Testing Notes

**GreetingResourceTest** - Basic endpoint tests
**NotFoundRedirectFilterTest** - Validates 404 → redirect behavior
**GroupMappingServiceTest** - Unit tests for multi-group AND matching logic (no Quarkus context needed; tests `matchesAllGroups` directly)

When writing tests for components that use OpenShiftClient, consider mocking the client or using test profiles with mock data.
