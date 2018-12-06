/*
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License
 * for the specific language governing permission and limitations under the
 * License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each
 * file and include the License file at legal/CDDLv1.0.txt. If applicable, add
 * the following below the CDDL Header, with the fields enclosed by brackets []
 * replaced by your own identifying information:
 * "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright © 2012 ForgeRock AS. All rights reserved.
 * Portions Copyright 2018 Wren Security.
 */
package org.forgerock.openidm.smartevent.core;

import org.forgerock.openidm.smartevent.EventEntry;
import org.forgerock.openidm.smartevent.Name;

/**
 * Used when event publishing is disabled Empty method calls may get optimized
 * out by the JIT
 * 
 */
public class DisabledPublisher implements PluggablePublisher {

    static final PluggablePublisher INSTANCE = new DisabledPublisher();
    static final DisabledEventEntry ENTRY = new DisabledEventEntry();

    public static PluggablePublisher getInstance() {
        return INSTANCE;
    }

    public final EventEntry start(Name eventName, Object payload, Object context) {
        return ENTRY;
    }

    public final void setResult(Object result, EventEntry delegate) {
    }

    public final void end(Name eventName, EventEntry callingEntry) {
    }
}
