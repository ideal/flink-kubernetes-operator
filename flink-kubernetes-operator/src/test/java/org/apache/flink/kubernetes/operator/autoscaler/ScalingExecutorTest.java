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

package org.apache.flink.kubernetes.operator.autoscaler;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.api.AbstractFlinkResource;
import org.apache.flink.kubernetes.operator.api.FlinkDeployment;
import org.apache.flink.kubernetes.operator.autoscaler.config.AutoScalerOptions;
import org.apache.flink.kubernetes.operator.autoscaler.metrics.EvaluatedScalingMetric;
import org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric;
import org.apache.flink.runtime.jobgraph.JobVertexID;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.kubernetes.operator.autoscaler.ScalingExecutor.PARALLELISM_OVERRIDES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test for scaling execution logic. */
@EnableKubernetesMockClient(crud = true)
public class ScalingExecutorTest {

    private ScalingExecutor scalingDecisionExecutor;
    private Configuration conf;
    private KubernetesClient kubernetesClient;
    private FlinkDeployment flinkDep;

    @BeforeEach
    public void setup() {
        scalingDecisionExecutor = new ScalingExecutor(kubernetesClient);
        conf = new Configuration();
        conf.set(AutoScalerOptions.STABILIZATION_INTERVAL, Duration.ZERO);
        conf.set(AutoScalerOptions.SCALING_ENABLED, true);
        conf.set(AutoScalerOptions.MAX_SCALE_DOWN_FACTOR, 1.);
        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ZERO);

        flinkDep = TestUtils.buildApplicationCluster();
        kubernetesClient.resource(flinkDep).createOrReplace();
        var jobStatus = flinkDep.getStatus().getJobStatus();
        jobStatus.setStartTime(String.valueOf(System.currentTimeMillis()));
        jobStatus.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        jobStatus.setState(JobStatus.RUNNING.name());
    }

    @Test
    public void testStabilizationPeriod() throws Exception {
        conf.set(AutoScalerOptions.STABILIZATION_INTERVAL, Duration.ofMinutes(1));
        conf.set(AutoScalerOptions.SCALING_EFFECTIVENESS_DETECTION_ENABLED, false);

        var metrics = Map.of(new JobVertexID(), evaluated(1, 110, 100));

        var scalingInfo = new AutoScalerInfo(new HashMap<>());
        var clock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
        var jobStatus = flinkDep.getStatus().getJobStatus();
        jobStatus.setUpdateTime(String.valueOf(clock.instant().toEpochMilli()));

        scalingDecisionExecutor.setClock(clock);
        assertFalse(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        clock = Clock.offset(clock, Duration.ofSeconds(30));
        scalingDecisionExecutor.setClock(clock);
        assertFalse(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        clock = Clock.offset(clock, Duration.ofSeconds(20));
        scalingDecisionExecutor.setClock(clock);
        assertFalse(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        clock = Clock.offset(clock, Duration.ofSeconds(20));
        scalingDecisionExecutor.setClock(clock);
        assertTrue(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        // A job should not be considered stable in a non-RUNNING state
        jobStatus.setState(JobStatus.FAILING.name());
        assertFalse(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        jobStatus.setState(JobStatus.RUNNING.name());
        jobStatus.setUpdateTime(String.valueOf(clock.instant().toEpochMilli()));
        assertFalse(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        clock = Clock.offset(clock, Duration.ofSeconds(59));
        scalingDecisionExecutor.setClock(clock);
        assertFalse(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));

        clock = Clock.offset(clock, Duration.ofSeconds(2));
        scalingDecisionExecutor.setClock(clock);
        assertTrue(scalingDecisionExecutor.scaleResource(flinkDep, scalingInfo, conf, metrics));
    }

    @Test
    public void testUtilizationBoundaries() {
        // Restart time should not affect utilization boundary
        conf.set(AutoScalerOptions.RESTART_TIME, Duration.ZERO);
        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ZERO);

        var flinkDep = TestUtils.buildApplicationCluster();
        kubernetesClient.resource(flinkDep).createOrReplace();

        var op1 = new JobVertexID();

        conf.set(AutoScalerOptions.TARGET_UTILIZATION, 0.6);
        conf.set(AutoScalerOptions.TARGET_UTILIZATION_BOUNDARY, 0.);

        var evaluated = Map.of(op1, evaluated(1, 70, 100));
        var scalingSummary = Map.of(op1, new ScalingSummary(2, 1, evaluated.get(op1)));
        assertFalse(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));

        conf.set(AutoScalerOptions.TARGET_UTILIZATION_BOUNDARY, 0.2);
        evaluated = Map.of(op1, evaluated(1, 70, 100));
        scalingSummary = Map.of(op1, new ScalingSummary(2, 1, evaluated.get(op1)));
        assertTrue(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));
        assertNull(getScaledParallelism(flinkDep));

        var op2 = new JobVertexID();
        evaluated =
                Map.of(
                        op1, evaluated(1, 70, 100),
                        op2, evaluated(1, 85, 100));
        scalingSummary =
                Map.of(
                        op1,
                        new ScalingSummary(1, 2, evaluated.get(op1)),
                        op2,
                        new ScalingSummary(1, 2, evaluated.get(op2)));

        assertFalse(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));

        evaluated =
                Map.of(
                        op1, evaluated(1, 70, 100),
                        op2, evaluated(1, 70, 100));
        scalingSummary =
                Map.of(
                        op1,
                        new ScalingSummary(1, 2, evaluated.get(op1)),
                        op2,
                        new ScalingSummary(1, 2, evaluated.get(op2)));
        assertTrue(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));

        // Test with backlog based scaling
        evaluated = Map.of(op1, evaluated(1, 70, 100, 15));
        scalingSummary = Map.of(op1, new ScalingSummary(1, 2, evaluated.get(op1)));
        assertFalse(ScalingExecutor.allVerticesWithinUtilizationTarget(evaluated, scalingSummary));
    }

    private Map<ScalingMetric, EvaluatedScalingMetric> evaluated(
            int parallelism, double target, double procRate, double catchupRate) {
        var metrics = new HashMap<ScalingMetric, EvaluatedScalingMetric>();
        metrics.put(ScalingMetric.PARALLELISM, EvaluatedScalingMetric.of(parallelism));
        metrics.put(ScalingMetric.MAX_PARALLELISM, EvaluatedScalingMetric.of(720));
        metrics.put(ScalingMetric.TARGET_DATA_RATE, new EvaluatedScalingMetric(target, target));
        metrics.put(ScalingMetric.CATCH_UP_DATA_RATE, EvaluatedScalingMetric.of(catchupRate));
        metrics.put(
                ScalingMetric.TRUE_PROCESSING_RATE, new EvaluatedScalingMetric(procRate, procRate));
        ScalingMetricEvaluator.computeProcessingRateThresholds(metrics, conf);
        return metrics;
    }

    private Map<ScalingMetric, EvaluatedScalingMetric> evaluated(
            int parallelism, double target, double procRate) {
        return evaluated(parallelism, target, procRate, 0.);
    }

    protected static Map<JobVertexID, Integer> getScaledParallelism(
            AbstractFlinkResource<?, ?> resource) {

        var conf = Configuration.fromMap(resource.getSpec().getFlinkConfiguration());
        var overrides = conf.get(PARALLELISM_OVERRIDES);
        if (overrides == null || overrides.isEmpty()) {
            return null;
        }

        var out = new HashMap<JobVertexID, Integer>();

        overrides.forEach((k, v) -> out.put(JobVertexID.fromHexString(k), Integer.parseInt(v)));
        return out;
    }
}
