/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.dlic.rest.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.rest.RestRequest.Method;
import org.opensearch.security.dlic.rest.validation.EndpointValidator;
import org.opensearch.security.dlic.rest.validation.RequestContentValidator;
import org.opensearch.security.dlic.rest.validation.RequestContentValidator.DataType;
import org.opensearch.security.dlic.rest.validation.ValidationResult;
import org.opensearch.security.securityconf.impl.CType;
import org.opensearch.threadpool.ThreadPool;

import static org.opensearch.security.dlic.rest.api.RequestHandler.methodNotImplementedHandler;
import static org.opensearch.security.dlic.rest.support.Utils.OPENDISTRO_API_DEPRECATION_MESSAGE;
import static org.opensearch.security.dlic.rest.support.Utils.addLegacyRoutesPrefix;
import static org.opensearch.security.dlic.rest.support.Utils.addRoutesPrefix;

public class RolesMappingApiAction extends AbstractApiAction {

    private static final List<Route> routes = addRoutesPrefix(
        ImmutableList.of(
            new Route(Method.GET, "/rolesmapping"),
            new Route(Method.GET, "/rolesmapping/{name}"),
            new Route(Method.DELETE, "/rolesmapping/{name}"),
            new Route(Method.PUT, "/rolesmapping/{name}"),
            new Route(Method.PATCH, "/rolesmapping"),
            new Route(Method.PATCH, "/rolesmapping/{name}")
        )
    );

    private static final List<DeprecatedRoute> deprecatedRoutes = addLegacyRoutesPrefix(
        ImmutableList.of(
            new DeprecatedRoute(Method.GET, "/rolesmapping", OPENDISTRO_API_DEPRECATION_MESSAGE),
            new DeprecatedRoute(Method.GET, "/rolesmapping/{name}", OPENDISTRO_API_DEPRECATION_MESSAGE),
            new DeprecatedRoute(Method.DELETE, "/rolesmapping/{name}", OPENDISTRO_API_DEPRECATION_MESSAGE),
            new DeprecatedRoute(Method.PUT, "/rolesmapping/{name}", OPENDISTRO_API_DEPRECATION_MESSAGE),
            new DeprecatedRoute(Method.PATCH, "/rolesmapping", OPENDISTRO_API_DEPRECATION_MESSAGE),
            new DeprecatedRoute(Method.PATCH, "/rolesmapping/{name}", OPENDISTRO_API_DEPRECATION_MESSAGE)
        )
    );

    @Inject
    public RolesMappingApiAction(
        final ClusterService clusterService,
        final ThreadPool threadPool,
        final SecurityApiDependencies securityApiDependencies
    ) {
        super(Endpoint.ROLESMAPPING, clusterService, threadPool, securityApiDependencies);
        this.requestHandlersBuilder.configureRequestHandlers(
            builder -> builder.onChangeRequest(Method.PATCH, this::processPatchRequest).override(Method.POST, methodNotImplementedHandler)
        );
    }

    @Override
    public List<Route> routes() {
        return routes;
    }

    @Override
    public List<DeprecatedRoute> deprecatedRoutes() {
        return deprecatedRoutes;
    }

    @Override
    protected CType<?> getConfigType() {
        return CType.ROLESMAPPING;
    }

    @Override
    protected EndpointValidator createEndpointValidator() {
        return new EndpointValidator() {
            @Override
            public Endpoint endpoint() {
                return endpoint;
            }

            @Override
            public RestApiAdminPrivilegesEvaluator restApiAdminPrivilegesEvaluator() {
                return securityApiDependencies.restApiAdminPrivilegesEvaluator();
            }

            @Override
            public ValidationResult<SecurityConfiguration> onConfigChange(SecurityConfiguration securityConfiguration) throws IOException {
                return EndpointValidator.super.onConfigChange(securityConfiguration).map(this::validateRole);
            }

            private ValidationResult<SecurityConfiguration> validateRole(final SecurityConfiguration securityConfiguration)
                throws IOException {
                // check here that role is not hidden for the mapping
                return loadConfiguration(CType.ROLES, false, false).map(
                    rolesConfiguration -> validateRoles(List.of(securityConfiguration.entityName()), rolesConfiguration)
                ).map(ignore -> ValidationResult.success(securityConfiguration));
            }

            @Override
            public ValidationResult<SecurityConfiguration> isAllowedToChangeImmutableEntity(SecurityConfiguration securityConfiguration)
                throws IOException {
                return EndpointValidator.super.isAllowedToChangeImmutableEntity(securityConfiguration).map(
                    this::isAllowedToChangeRoleMappingWithRestAdminPermissions
                );
            }

            public ValidationResult<SecurityConfiguration> isAllowedToChangeRoleMappingWithRestAdminPermissions(
                SecurityConfiguration securityConfiguration
            ) throws IOException {
                return loadConfiguration(CType.ROLES, false, false).map(
                    rolesConfiguration -> isAllowedToChangeEntityWithRestAdminPermissions(
                        SecurityConfiguration.of(securityConfiguration.entityName(), rolesConfiguration)
                    )
                ).map(ignore -> ValidationResult.success(securityConfiguration));
            }

            @Override
            public RequestContentValidator createRequestContentValidator(Object... params) {
                return RequestContentValidator.of(new RequestContentValidator.ValidationContext() {
                    @Override
                    public Object[] params() {
                        return params;
                    }

                    @Override
                    public Settings settings() {
                        return securityApiDependencies.settings();
                    }

                    @Override
                    public Set<String> mandatoryOrKeys() {
                        return ImmutableSet.of("and_backend_roles", "backend_roles", "hosts", "users");
                    }

                    @Override
                    public Map<String, DataType> allowedKeys() {
                        final ImmutableMap.Builder<String, DataType> allowedKeys = ImmutableMap.builder();
                        if (isCurrentUserAdmin()) {
                            allowedKeys.put("hidden", DataType.BOOLEAN);
                            allowedKeys.put("reserved", DataType.BOOLEAN);
                        }
                        return allowedKeys.put("backend_roles", DataType.ARRAY)
                            .put("and_backend_roles", DataType.ARRAY)
                            .put("hosts", DataType.ARRAY)
                            .put("users", DataType.ARRAY)
                            .put("description", DataType.STRING)
                            .build();
                    }
                });
            }
        };
    }

}
