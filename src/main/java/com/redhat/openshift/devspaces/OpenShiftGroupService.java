package com.redhat.openshift.devspaces;

import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.api.model.Group;
import io.fabric8.openshift.api.model.GroupList;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class OpenShiftGroupService {

    private static final Logger LOG = Logger.getLogger(OpenShiftGroupService.class);
    private final OpenShiftClient openshiftClient;

    /**
     * Fabric8 retries a failed request 10 times with backoff by default, so a single
     * call against an unreachable API blocks for roughly 20 seconds. That is too long
     * on both paths here: the readiness probe runs every 10s with a 5s timeout, so
     * the kubelet would abandon each call while the server thread kept retrying,
     * piling up threads during precisely the outage the probe exists to detect. A
     * user request would hang just as long before silently falling through to an
     * empty mapping. Three attempts is enough to ride out a blip.
     */
    private static final int REQUEST_RETRY_BACKOFF_LIMIT = 2;

    public OpenShiftGroupService() {
        // Initialize OpenShift client using service account token
        // The client will automatically use the service account token from /var/run/secrets/kubernetes.io/serviceaccount/token
        Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestRetryBackoffLimit(REQUEST_RETRY_BACKOFF_LIMIT)
                .build();
        this.openshiftClient = new KubernetesClientBuilder()
                .withConfig(config)
                .build()
                .adapt(OpenShiftClient.class);
    }

    /**
     * Minimal call proving the groups API is reachable and the ClusterRole grants
     * list access. Uses limit=1 so the cost does not grow with the number of groups
     * on the cluster — this runs on every readiness probe.
     *
     * The other methods here swallow exceptions and return empty lists, so they
     * cannot tell "API unreachable" apart from "no groups". This one propagates.
     *
     * @throws RuntimeException (Fabric8 KubernetesClientException) if the API call fails
     */
    void verifyGroupsApiAccessible() {
        openshiftClient.groups().list(new ListOptionsBuilder().withLimit(1L).build());
    }

    /**
     * Get all OpenShift groups from the cluster
     * @return List of all group names
     */
    public List<String> getAllGroups() {
        try {
            GroupList groupList = openshiftClient.groups().list();
            return groupList.getItems().stream()
                    .map(group -> group.getMetadata().getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to list OpenShift groups");
            return new ArrayList<>();
        }
    }

    /**
     * Check if a user belongs to a specific group
     * @param groupName The group name to check
     * @param userName The user name to check
     * @return true if user belongs to the group, false otherwise
     */
    public boolean userBelongsToGroup(String groupName, String userName) {
        try {
            Group group = openshiftClient.groups().withName(groupName).get();
            if (group == null) {
                return false;
            }
            
            // Check if user is in the group's users list
            List<String> users = group.getUsers();
            return users != null && users.contains(userName);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to check if user %s belongs to group %s", userName, groupName);
            return false;
        }
    }

    /**
     * Get all groups that a user belongs to
     * @param userName The user name
     * @return List of group names the user belongs to
     */
    public List<String> getUserGroups(String userName) {
        try {
            GroupList groupList = openshiftClient.groups().list();
            return groupList.getItems().stream()
                    .filter(group -> {
                        List<String> users = group.getUsers();
                        return users != null && users.contains(userName);
                    })
                    .map(group -> group.getMetadata().getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to get groups for user %s", userName);
            return new ArrayList<>();
        }
    }
}

