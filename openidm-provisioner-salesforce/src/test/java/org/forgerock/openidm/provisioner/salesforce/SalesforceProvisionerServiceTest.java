/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013-2014 ForgeRock AS. All Rights Reserved
 */

package org.forgerock.openidm.provisioner.salesforce;

import java.awt.*;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResult;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.Resource;
import org.forgerock.json.resource.RootContext;
import org.forgerock.openidm.router.RouterRegistry;
import org.forgerock.openidm.provisioner.salesforce.internal.GuiceSalesforceModule;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import com.google.inject.Inject;

/**
 * A SalesforceProvisionerServiceTest tests the Data API
 * http://wiki.developerforce.com/page/Data_Integration
 *
 * @author Laszlo Hordos
 */
@Guice(modules = GuiceSalesforceModule.class)
public class SalesforceProvisionerServiceTest {

    @Inject
    @Nullable
    RouterRegistry routerRegistry = null;

    @BeforeClass
    public void beforeClass() throws Exception {
        if (null == routerRegistry) {
            throw new SkipException("Test is skipped because config file not exits at: ");
        }
    }

    @Test
    public void testCreateInstance() throws Exception {

    }

    @Test(groups = { "timeout" })
    public void testTimeout() throws Exception {
        ReadRequest readRequest = Requests.newReadRequest("/system/test/sobjects/User/describe");
        int i = 0;
        do {
            Resource r = routerRegistry.getConnection("test").read(new RootContext(), readRequest);
            Assert.assertNotNull(r.getContent().getObject());
            Thread.sleep(16 * 60 * 1000);
            Toolkit.getDefaultToolkit().beep();
        } while (i++ < 5);
    }

    @Test
    public void testQueryCollection() throws Exception {
        QueryRequest queryRequest = Requests.newQueryRequest("/system/test/sobjects/User");
        queryRequest.setQueryId("active-only");
        Set<Resource> resourcesFirst = new HashSet<Resource>();
        QueryResult resultFirst =
                routerRegistry.getConnection("test").query(new RootContext(), queryRequest,
                        resourcesFirst);
        Assert.assertFalse(resourcesFirst.isEmpty());

        queryRequest = Requests.newQueryRequest("/system/test/query");
        queryRequest
                .setQueryExpression("select Id from User where IsActive = true AND Alias != 'Chatter'");
        Set<Resource> resourceSecond = new HashSet<Resource>();
        QueryResult resultSecond =
                routerRegistry.getConnection("test").query(new RootContext(), queryRequest,
                        resourceSecond);
        Assert.assertFalse(resourceSecond.isEmpty());

        Assert.assertEquals(resultFirst.getRemainingPagedResults(), resultSecond
                .getRemainingPagedResults());
        Assert.assertEquals(resultFirst.getPagedResultsCookie(), resultSecond
                .getPagedResultsCookie());

        Resource resourceFirst = resourcesFirst.iterator().next();
        ReadRequest readRequest =
                Requests.newReadRequest("/system/test/sobjects/User", resourceFirst.getId());
        Resource resourceRead =
                routerRegistry.getConnection("test").read(new RootContext(), readRequest);
        Assert.assertEquals(resourceFirst.getId(), resourceRead.getId());

    }

    @Test
    public void testQuery() throws Exception {
        QueryRequest queryRequest = Requests.newQueryRequest("/system/test/query");
        queryRequest
                .setQueryExpression("select Id from User where IsActive = true AND Alias != 'Chatter'");
        Set<Resource> resource = new HashSet<Resource>();
        QueryResult result =
                routerRegistry.getConnection("test").query(new RootContext(), queryRequest,
                        resource);
        Assert.assertFalse(resource.isEmpty());
    }

    @Test
    public void testUpdateInstance() throws Exception {

    }

    @Test
    public void testDeleteInstance() throws Exception {

    }

    @Test
    public void testActionCollection() throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("/system/test", "test");
        JsonValue result =
                routerRegistry.getConnection("test").action(new RootContext(), actionRequest);
        Assert.assertTrue(result.get("ok").asBoolean(), "System is not available");
    }

    @Test
    public void testActionInstance() throws Exception {

    }

    @Test
    public void testPatchInstance() throws Exception {

    }
}
