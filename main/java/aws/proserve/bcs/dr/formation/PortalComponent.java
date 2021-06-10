// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation;

import dagger.Component;
import software.amazon.awscdk.core.App;

import javax.inject.Singleton;

@Singleton
@Component(modules = PortalModule.class)
interface PortalComponent {
    App app();

    Application.Touch touch();
}
