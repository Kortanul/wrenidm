/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017 ForgeRock AS.
 */
package org.forgerock.openidm.repo.opendj.impl;

import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourcePath;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Response;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.opendj.ldap.Connection;
import org.forgerock.opendj.ldap.ConnectionFactory;
import org.forgerock.opendj.ldap.LdapException;
import org.forgerock.opendj.rest2ldap.AuthenticatedConnectionContext;
import org.forgerock.opendj.rest2ldap.Resource;
import org.forgerock.opendj.rest2ldap.Rest2Ldap;
import org.forgerock.opendj.rest2ldap.Rest2LdapJsonConfigurator;
import org.forgerock.openidm.config.enhanced.EnhancedConfig;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.repo.RepoBootService;
import org.forgerock.openidm.repo.RepositoryService;
import org.forgerock.services.context.Context;
import org.forgerock.services.context.RootContext;
import org.forgerock.util.Function;
import org.forgerock.util.Options;
import org.forgerock.util.Reject;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository service implementation using OpenDJ backend
 */
@Component(name = OpenDJRepoService.PID, immediate=true, policy=ConfigurationPolicy.REQUIRE, enabled=true)
@Service (value = {RepositoryService.class, RequestHandler.class})
@Properties({
    @Property(name = "service.description", value = "Repository Service using OpenDJ"),
    @Property(name = "service.vendor", value = ServerConstants.SERVER_VENDOR_NAME),
    @Property(name = ServerConstants.ROUTER_PREFIX, value = "/repo/*"),
    @Property(name = "db.type", value = "OpenDJ") })
public class OpenDJRepoService implements RepositoryService, RequestHandler, RepoBootService {

    public static final String PID = "org.forgerock.openidm.repo.opendj";

    private static final Logger logger = LoggerFactory.getLogger(OpenDJRepoService.class);

    private static final JsonValue NO_MAPPINGS = json(object());

    /**
     * The current OpenDJ configuration
     */
    private JsonValue existingConfig;

    /**
     * Used for parsing the configuration
     */
    @Reference
    private EnhancedConfig enhancedConfig;

    /**
     * The LDAP connection factory used for accessing OpenDJ
     */
    @Reference
    private ConnectionFactory ldapFactory;

    /**
     * Default handler used for resources that have not been explicitly configured
     * in the /resourceMapping of repo config
     */
    private TypeHandler defaultTypeHandler;

    /**
     * Map of handlers for each configured type
     */
    private Map<String, TypeHandler> typeHandlers;

    static RepoBootService getRepoBootService(final ConnectionFactory connectionFactory, final JsonValue config) {
        OpenDJRepoService bootSvc = new OpenDJRepoService();
        bootSvc.ldapFactory = connectionFactory;
        bootSvc.init(config);

        return bootSvc;
    }

    /**
     * Get the handler associated with the given uri
     * @param uri The uri of the request
     * @return The associated type handler
     */
    private TypeHandler getTypeHandler(final String uri) {
        logger.debug("Getting type handler for {}", uri);
        TypeHandler handler = typeHandlers.get(uri);

        if (handler != null) {
            logger.debug("Found existing handler for {}", uri);
            return handler;
        } else {
            handler = defaultTypeHandler;

            for (String type : typeHandlers.keySet()) {
                if (uri.startsWith(type)) {
                    handler = typeHandlers.get(type);
                    logger.debug("Using type handler {} for {}", type, uri);
                }
            }

            typeHandlers.put(uri, handler);
            return handler;
        }
    }

    @Activate
    void activate(final ComponentContext compContext) throws Exception {
        logger.info("Activating Service with configuration {}", compContext.getProperties());

        try {
            existingConfig = enhancedConfig.getConfigurationAsJson(compContext);
        } catch (RuntimeException ex) {
            logger.warn("Configuration invalid and could not be parsed, can not start OpenDJ repository: "
                    + ex.getMessage(), ex);
            throw ex;
        }

        //  Initialize the repo service
        init(existingConfig);

        logger.info("Repository started.");
    }

    @Deactivate
    void deactivate(final ComponentContext ctx) {
    }

    void init(final JsonValue config) {
        final JsonValue queries = config.get("queries").required();

        /*
         * Mappings
         */

        // IDM-specific handler config (outside scope of rest2ldap)
        final JsonValue resourceMappings = config.get("resourceMapping").required().expect(Map.class);

        final Options rest2LdapOptions = Rest2LdapJsonConfigurator.configureOptions(
                config.get("rest2LdapOptions").required().expect(Map.class));

        final List<Resource> resources = Rest2LdapJsonConfigurator.configureResources(
                config.get("resources").required().expect(Map.class));

        final Map<String, TypeHandler> typeHandlers = new HashMap<>();

        final Rest2Ldap rest2Ldap = Rest2Ldap.rest2Ldap(rest2LdapOptions, resources);
        final RequestHandler repoHandler = rest2Ldap.newRequestHandlerFor("repo");

        final JsonValue genericQueries = queries.get("generic").required();

        final JsonValue defaultHandlerConfig = resourceMappings.get("defaultMapping").expect(Map.class);
        final ResourcePath defaultRepoResourcePath = new ResourcePath("default");
        defaultTypeHandler = new GenericDJTypeHandler(
                defaultRepoResourcePath, repoHandler, defaultHandlerConfig, genericQueries, config.get("commands"));

        // Generic mappings
        final JsonValue genericMappings = resourceMappings.get("genericMapping").expect(Map.class);
        for (String type : genericMappings.defaultTo(NO_MAPPINGS).keys()) {
            final JsonValue handlerConfig = genericMappings.get(type);

            // strip wildcard for matching
            if (type.endsWith("/*")) {
                type = type.substring(0, type.length() - 1);
            }

            // The path to this resource on the rest2ldap router
            final ResourcePath path =
                    new ResourcePath(handlerConfig.get("resource").required().asString().split("/"));
            final ExplicitDJTypeHandler typeHandler = new GenericDJTypeHandler(
                    path, repoHandler, handlerConfig,
                    queries.get("generic").required(), config.get("commands"));

            typeHandlers.put(type, typeHandler);
        }

        // Explicit mappings
        final JsonValue explicitMappings = resourceMappings.get("explicitMapping").expect(Map.class);
        for (String type : explicitMappings.defaultTo(NO_MAPPINGS).keys()) {
            final JsonValue handlerConfig = explicitMappings.get(type);
            // The path to this resource on the rest2ldap router
            final ResourcePath path = new ResourcePath(type.split("/"));
            final ExplicitDJTypeHandler typeHandler = new ExplicitDJTypeHandler(path, repoHandler, handlerConfig,
                    queries.get("explicit").required(), config.get("commands"));

            typeHandlers.put(type, typeHandler);
        }

        this.typeHandlers = typeHandlers;
    }

    /**
     * Function wrapper that passes in a context containing a {@link AuthenticatedConnectionContext}
     * and closes the connection after the function promise is complete.
     *
     * @param context The base context to chain the {@link AuthenticatedConnectionContext} to
     * @param function The function to use the provided context
     *
     * @return The return value of function
     */
    private <R extends Response> Promise<R, ResourceException> withConnectionContext(
            final Context context,
            final Function<Context, Promise<R, ResourceException>, NeverThrowsException> function) {
        try {
            // must check context before acquiring a connection
            Reject.checkNotNull(context);
            final Connection connection = ldapFactory.getConnection();
            final AuthenticatedConnectionContext authCtx =
                    new AuthenticatedConnectionContext(context, connection);

            return function.apply(authCtx).then(new Function<R, R, ResourceException>() {
                @Override
                public R apply(final R response) throws ResourceException {
                    connection.close();
                    return response;
                }
            });
        } catch (LdapException e) {
            return new InternalServerErrorException("Failed to acquire LDAP connection", e).asPromise();
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleCreate(final Context context, final CreateRequest request) {
        return withConnectionContext(context, new Function<Context, Promise<ResourceResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<ResourceResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(request.getResourcePath()).handleCreate(context, request);
            }
        });
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleRead(final Context context, final ReadRequest request) {
        return withConnectionContext(context, new Function<Context, Promise<ResourceResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<ResourceResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(request.getResourcePathObject().parent().toString()).handleRead(context, request);
            }
        });
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleUpdate(final Context context, final UpdateRequest request) {
        return withConnectionContext(context, new Function<Context, Promise<ResourceResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<ResourceResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(request.getResourcePathObject().parent().toString()).handleUpdate(context, request);
            }
        });
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handlePatch(final Context context, final PatchRequest request) {
        return withConnectionContext(context, new Function<Context, Promise<ResourceResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<ResourceResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(request.getResourcePath()).handlePatch(context, request);
            }
        });
    }

    @Override
    public Promise<ActionResponse, ResourceException> handleAction(final Context context, final ActionRequest request) {
        return withConnectionContext(context, new Function<Context, Promise<ActionResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<ActionResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(request.getResourcePath()).handleAction(context, request);
            }
        });
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleDelete(final Context context, final DeleteRequest request) {
        return withConnectionContext(context, new Function<Context, Promise<ResourceResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<ResourceResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(request.getResourcePathObject().parent().toString()).handleDelete(context, request);
            }
        });
    }

    @Override
    public Promise<QueryResponse, ResourceException> handleQuery(final Context context, final QueryRequest queryRequest, final QueryResourceHandler queryResourceHandler) {
        return withConnectionContext(context, new Function<Context, Promise<QueryResponse, ResourceException>, NeverThrowsException>() {
            @Override
            public Promise<QueryResponse, ResourceException> apply(final Context context) throws NeverThrowsException {
                return getTypeHandler(queryRequest.getResourcePath()).handleQuery(context, queryRequest, queryResourceHandler);
            }
        });
    }

    /*
     * RepositoryService methods just forward to the RequestHandler and block until complete.
     * Must pass in a new RootContext to chain the AuthenticatedConnectionContext
     */

    @Override
    public ResourceResponse create(final CreateRequest request) throws ResourceException {
        try {
            return handleCreate(new RootContext(), request).getOrThrow();
        } catch (InterruptedException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Override
    public ResourceResponse read(final ReadRequest request) throws ResourceException {
        try {
            return handleRead(new RootContext(), request).getOrThrow();
        } catch (InterruptedException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Override
    public ResourceResponse update(final UpdateRequest request) throws ResourceException {
        try {
            return handleUpdate(new RootContext(), request).getOrThrow();
        } catch (InterruptedException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Override
    public ResourceResponse delete(final DeleteRequest request) throws ResourceException {
        try {
            return handleDelete(new RootContext(), request).getOrThrow();
        } catch (InterruptedException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Override
    public List<ResourceResponse> query(final QueryRequest request) throws ResourceException {
        final List<ResourceResponse> results = new ArrayList<>();
        final QueryResourceHandler handler = new QueryResourceHandler() {
            @Override
            public boolean handleResource(ResourceResponse resourceResponse) {
                results.add(resourceResponse);
                return true;
            }
        };

        try {
            handleQuery(new RootContext(), request, handler).getOrThrow();

            return results;
        } catch (InterruptedException e) {
            throw new InternalServerErrorException(e);
        }
    }
}