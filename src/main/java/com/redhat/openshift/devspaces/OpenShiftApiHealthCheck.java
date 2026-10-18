package com.redhat.openshift.devspaces;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness check for the OpenShift groups API.
 *
 * This is the failure mode the old probes could not see: the servlet container is
 * up, so the pod looks healthy, but group lookups fail and every user falls through
 * to an empty mapping. Covers both API unreachability and a missing or revoked
 * ClusterRoleBinding.
 *
 * Note this gates readiness on an external dependency: an API outage takes the pod
 * out of the Service rather than serving degraded results. That is intentional —
 * the app has no useful behaviour without group data — but with replicas: 1 it
 * means an API blip is a user-visible outage. The probe's failureThreshold absorbs
 * transient errors. Drop @Readiness here to make it observational only.
 */
@Readiness
@ApplicationScoped
public class OpenShiftApiHealthCheck implements HealthCheck {

    static final String NAME = "openshift-groups-api";

    @Inject
    OpenShiftGroupService openShiftGroupService;

    @Override
    public HealthCheckResponse call() {
        try {
            openShiftGroupService.verifyGroupsApiAccessible();
            return HealthCheckResponse.named(NAME).up().build();
        } catch (Exception e) {
            return HealthCheckResponse.named(NAME)
                    .withData("error", String.valueOf(e.getMessage()))
                    .down()
                    .build();
        }
    }
}
