package com.redhat.openshift.devspaces;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness check for the group-mapping ConfigMap.
 *
 * Without this the pod reports ready whenever the HTTP listener is up, including
 * when the ConfigMap is unmounted or holds invalid JSON — in which case every user
 * is silently redirected nowhere.
 */
@Readiness
@ApplicationScoped
public class GroupMappingHealthCheck implements HealthCheck {

    static final String NAME = "group-mapping-configmap";

    @Inject
    GroupMappingService groupMappingService;

    @Override
    public HealthCheckResponse call() {
        try {
            int entries = groupMappingService.verifyMappingReadable();
            return HealthCheckResponse.named(NAME)
                    .withData("entries", entries)
                    .up()
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named(NAME)
                    .withData("error", String.valueOf(e.getMessage()))
                    .down()
                    .build();
        }
    }
}
