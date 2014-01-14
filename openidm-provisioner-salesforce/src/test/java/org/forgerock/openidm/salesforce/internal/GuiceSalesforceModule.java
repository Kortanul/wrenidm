/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013-2014 ForgeRock AS. All Rights Reserved
 */

package org.forgerock.openidm.salesforce.internal;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.router.RouterRegistry;
import org.forgerock.openidm.salesforce.internal.data.SalesforceRequestHandler;
import org.testng.Reporter;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * A GuiceSalesforceModule activate a single SalesforceRequestHandler for
 * testing.
 *
 * @author Laszlo Hordos
 */
public class GuiceSalesforceModule extends AbstractModule {

    private RouterRegistry registry;
    private final AtomicBoolean active = new AtomicBoolean(false);

    @Override
    protected void configure() {
    }

    @Provides
    @Singleton
    public RouterRegistry provideRouterRegistry() {
        if (null == registry) {
            synchronized (this) {
                if (null == registry && active.compareAndSet(false, true)) {
                    File configFile = new File(System.getProperty("user.home"), "salesforce.json");
                    try {
                        if (configFile.exists()) {
                            JsonValue config =
                                    new JsonValue(SalesforceConnection.mapper.readValue(configFile,
                                            Map.class));
                            SalesforceRequestHandler handler = new SalesforceRequestHandler();
                            registry = new TestUtil.TestRouterRegistry();
                            TestUtil.setField(handler, "routerRegistryService", registry);
                            TestUtil.initCryptoService();
                            TestUtil.activate(handler, "test", config);
                        }
                    } catch (Exception e) {
                        /* Log it */
                        Reporter.log("TEST WAS CALLED: Create config file at: "
                                + configFile.getAbsolutePath());
                    }
                }
            }
        }
        return registry;
    }

    public void dispose(){

    }

}
