package com.redhat.openshift.devspaces;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.hasItems;

/**
 * The readiness checks depend on a mounted ConfigMap and a reachable OpenShift API,
 * neither of which exists under test. These assertions therefore cover wiring and
 * reachability — that both checks are registered and that the endpoints are not
 * swallowed by Redirect404Filter — rather than asserting UP.
 */
@QuarkusTest
public class HealthCheckTest {

    @Test
    public void livenessIsUpWithoutExternalDependencies() {
        given()
          .when().get("/q/health/live")
          .then()
             .statusCode(200)
             .body("status", is("UP"));
    }

    @Test
    public void readinessReportsBothChecks() {
        given()
          .when().get("/q/health/ready")
          .then()
             // 200 if both dependencies happen to be reachable, 503 otherwise.
             .statusCode(anyOf(is(200), is(503)))
             .body("checks.name", hasItems(
                     GroupMappingHealthCheck.NAME,
                     OpenShiftApiHealthCheck.NAME));
    }

    /**
     * Redirect404Filter rewrites 404 to a 302 for every servlet path. Health lives on
     * the Vert.x non-application root, so it must not be rewritten — a 302 here would
     * make the kubelet read every failure as success.
     */
    @Test
    public void healthEndpointIsNotRewrittenByThe404Filter() {
        given().redirects().follow(false)
          .when().get("/q/health/ready")
          .then()
             .statusCode(anyOf(is(200), is(503)));
    }
}
