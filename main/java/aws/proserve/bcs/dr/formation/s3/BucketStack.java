// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.s3;

import aws.proserve.bcs.dr.s3.S3Constants;
import aws.proserve.bcs.formation.BucketProvider;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.ssm.StringParameter;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BucketStack extends Stack implements BucketProvider {

    private final Bucket bucket;

    @Inject
    BucketStack(App app) {
        super(app, "DRPortal-Bucket");

        this.bucket = Bucket.Builder
                .create(this, "Common")
                .encryption(BucketEncryption.KMS_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        StringParameter.Builder
                .create(this, "BucketName")
                .parameterName(S3Constants.PARAM_BUCKET)
                .stringValue(bucket.getBucketName())
                .build();
    }

    @Override
    public Bucket getBucket() {
        return bucket;
    }
}
