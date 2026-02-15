/*
 *  Copyright (c) 2025 Contributors
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Development bootstrap extension
 *
 */

package org.eclipse.edc.identityhub.dev;

import org.eclipse.edc.identityhub.spi.participantcontext.IdentityHubParticipantContextService;
import org.eclipse.edc.identityhub.spi.participantcontext.model.KeyDescriptor;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantManifest;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Development-only extension that bootstraps a super-user on startup.
 * This solves the chicken-and-egg problem: the Identity API requires an x-api-key
 * to create participants, but no participants exist on a fresh install.
 * <p>
 * DO NOT use in production. In production, use a proper provisioning mechanism.
 */
@Extension(SuperUserSeedExtension.NAME)
public class SuperUserSeedExtension implements ServiceExtension {

    public static final String NAME = "Super User Seed Extension (DEV)";

    @Setting(description = "The participant context ID for the super-user", defaultValue = "super-user", key = "edc.ih.superuser.id")
    private String superUserId;

    @Setting(description = "The DID for the super-user", defaultValue = "did:web:super-user", key = "edc.ih.superuser.did")
    private String superUserDid;

    @Inject
    private IdentityHubParticipantContextService participantContextService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start() {
        var existing = participantContextService.getParticipantContext(superUserId);
        if (existing.succeeded()) {
            getMonitor().info("Super-user '%s' already exists, skipping bootstrap.".formatted(superUserId));
            return;
        }

        var manifest = ParticipantManifest.Builder.newInstance()
                .participantContextId(superUserId)
                .did(superUserDid)
                .active(true)
                .roles(List.of("admin"))
                .key(KeyDescriptor.Builder.newInstance()
                        .keyId(superUserId + "-key")
                        .privateKeyAlias(superUserId + "-alias")
                        .keyGeneratorParams(Map.of("algorithm", "EC", "curve", "secp256r1"))
                        .active(true)
                        .build())
                .serviceEndpoints(Set.of())
                .build();

        var result = participantContextService.createParticipantContext(manifest);
        if (result.succeeded()) {
            var response = result.getContent();
            getMonitor().info("==========================================================");
            getMonitor().info("  DEV SUPER-USER BOOTSTRAPPED");
            getMonitor().info("  Participant ID: %s".formatted(superUserId));
            getMonitor().info("  DID:            %s".formatted(superUserDid));
            getMonitor().info("  API Key:        %s".formatted(response.apiKey()));
            getMonitor().info("==========================================================");
        } else {
            getMonitor().severe("Failed to bootstrap super-user: %s".formatted(result.getFailureDetail()));
        }
    }

    private org.eclipse.edc.spi.monitor.Monitor getMonitor() {
        return monitor;
    }

    @Inject
    private org.eclipse.edc.spi.monitor.Monitor monitor;
}
