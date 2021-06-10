# Disaster Recovery Factory - Formation

## Introduction
This project is published as a solution in the Amazon Web Services Solutions Library.
For more information, including how to deploy it into your AWS account, please visit:
- https://www.amazonaws.cn/en/solutions/disaster-recovery-factory

### This Package
This package contains classes for [AWS Cloud Development Kit](https://aws.amazon.com/cdk/) (CDK) based deployment for Disaster Recovery Factory.
It also contains several bash scripts under `bash/` folder to facilitate resource provisioning.
Note that as an end user, you do not need to directly use this package.
Deploy this project into your AWS account through the one-click button in the solution page.

### Synthesize Templates
The following environmental parameters are checked during synthesizing CloudFormation templates:

|Parameter|Value|Notes|
|---|---|---|
|`REGION`|AWS region, such as `cn-north-1`|If it starts with `cn-`, a China-specific endpoint is used for VPC endpoints.|
|`DBDUMP_MYSQL_DUMP_IMAGE`|Public ECR image URL|If undefined, it creates a ECR repository and refers there.|
|`DYNAMO_IMAGE`|Public ECR image URL|If undefined, it creates a ECR repository and refers there.|
|`S3_IMAGE`|Public ECR image URL|If undefined, it creates a ECR repository and refers there.|

The `cdk.json` is defined as
```json
{
  "app": "java -jar $CDK_JAR"
}
```

Therefore, you need to export the path to the executable `jar` file before running `cdk`, such as
```bash
export CDK_JAR=build/BCSDisasterRecoveryPortalFormation-1.0-exe.jar
```

## AWS Blogs
The following blog articles introduce in depth how this solution works and how to make the most out of it.
- [Use Disaster Recovery Factory to efficiently manage instance disaster recovery configurations](https://aws.amazon.com/cn/blogs/china/use-cloud-disaster-recovery-management-tools-to-efficiently-manage-instance-disaster-recovery-configuration/) (March 2021)
- [Migrate and protect EC2 instances by Disaster Recovery Factory](https://aws.amazon.com/cn/blogs/china/gcr-blog-migrate-and-protect-ec2-instances-using-cloud-disaster-management-tools/) (July 2020)

