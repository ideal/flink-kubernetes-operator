/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.observer.deployment;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.api.status.FlinkDeploymentStatus;
import org.apache.flink.kubernetes.operator.api.status.JobStatus;
import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;
import org.apache.flink.kubernetes.operator.controller.FlinkResourceContext;
import org.apache.flink.kubernetes.operator.exception.UnknownJobException;
import org.apache.flink.kubernetes.operator.observer.ClusterHealthObserver;
import org.apache.flink.kubernetes.operator.observer.JobStatusObserver;
import org.apache.flink.kubernetes.operator.observer.SavepointObserver;
import org.apache.flink.kubernetes.operator.reconciler.ReconciliationUtils;
import org.apache.flink.kubernetes.operator.utils.EventRecorder;
import org.apache.flink.runtime.client.JobStatusMessage;

import java.util.List;
import java.util.Optional;

import static org.apache.flink.kubernetes.operator.config.KubernetesOperatorConfigOptions.OPERATOR_CLUSTER_HEALTH_CHECK_ENABLED;

/** The observer of {@link org.apache.flink.kubernetes.operator.config.Mode#APPLICATION} cluster. */
public class ApplicationObserver extends AbstractFlinkDeploymentObserver {

    private final SavepointObserver<FlinkDeployment, FlinkDeploymentStatus> savepointObserver;
    private final JobStatusObserver<FlinkDeployment> jobStatusObserver;

    private final ClusterHealthObserver clusterHealthObserver;

    public ApplicationObserver(FlinkConfigManager configManager, EventRecorder eventRecorder) {
        super(configManager, eventRecorder);
        this.savepointObserver = new SavepointObserver<>(configManager, eventRecorder);
        this.jobStatusObserver = new ApplicationJobObserver(configManager, eventRecorder);
        this.clusterHealthObserver = new ClusterHealthObserver();
    }

    @Override
    protected void observeFlinkCluster(FlinkResourceContext<FlinkDeployment> ctx) {
        logger.debug("Observing application cluster");
        boolean jobFound = jobStatusObserver.observe(ctx);
        if (jobFound) {
            var observeConfig = ctx.getObserveConfig();
            savepointObserver.observeSavepointStatus(ctx);
            if (observeConfig.getBoolean(OPERATOR_CLUSTER_HEALTH_CHECK_ENABLED)) {
                clusterHealthObserver.observe(ctx);
            }
        }
    }

    private class ApplicationJobObserver extends JobStatusObserver<FlinkDeployment> {
        public ApplicationJobObserver(
                FlinkConfigManager configManager, EventRecorder eventRecorder) {
            super(configManager, eventRecorder);
        }

        @Override
        public void onTimeout(FlinkResourceContext<FlinkDeployment> ctx) {
            observeJmDeployment(ctx);
        }

        @Override
        protected Optional<JobStatusMessage> filterTargetJob(
                JobStatus status, List<JobStatusMessage> clusterJobStatuses) {
            if (!clusterJobStatuses.isEmpty()) {
                return Optional.of(clusterJobStatuses.get(0));
            }
            return Optional.empty();
        }

        @Override
        protected void onTargetJobNotFound(FlinkDeployment resource, Configuration config) {
            // This should never happen for application clusters, there is something
            // wrong
            setUnknownJobError(resource);
        }

        /**
         * We found a job on an application cluster that doesn't match the expected job. Trigger
         * error.
         *
         * @param deployment Application deployment.
         */
        private void setUnknownJobError(FlinkDeployment deployment) {
            deployment
                    .getStatus()
                    .getJobStatus()
                    .setState(org.apache.flink.api.common.JobStatus.RECONCILING.name());
            String err = "Unrecognized Job for Application deployment";
            logger.error(err);
            ReconciliationUtils.updateForReconciliationError(
                    deployment,
                    new UnknownJobException(err),
                    configManager.getOperatorConfiguration());
            eventRecorder.triggerEvent(
                    deployment,
                    EventRecorder.Type.Warning,
                    EventRecorder.Reason.Missing,
                    EventRecorder.Component.Job,
                    err);
        }
    }
}
