/*
 * Copyright 2017 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.micrometer;

import io.micrometer.common.lang.Nullable;
import io.micrometer.core.annotation.Incubating;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.cumulative.CumulativeCounter;
import io.micrometer.core.instrument.cumulative.CumulativeDistributionSummary;
import io.micrometer.core.instrument.cumulative.CumulativeFunctionCounter;
import io.micrometer.core.instrument.cumulative.CumulativeFunctionTimer;
import io.micrometer.core.instrument.cumulative.CumulativeTimer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramGauges;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.internal.DefaultGauge;
import io.micrometer.core.instrument.internal.DefaultLongTaskTimer;
import io.micrometer.core.instrument.internal.DefaultMeter;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.step.StepCounter;
import io.micrometer.core.instrument.step.StepDistributionSummary;
import io.micrometer.core.instrument.step.StepFunctionCounter;
import io.micrometer.core.instrument.step.StepFunctionTimer;
import io.micrometer.core.instrument.step.StepTimer;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * A minimal meter registry implementation primarily used for tests.
 *
 * This is a copy of the Micrometer SimpleMeterRegistry with additions made to enable for instance percentiles and other
 * Entryscape specific settings.
 */
public class EntryscapeSimpleMeterRegistry extends MeterRegistry {

	private final SimpleConfig config;

	public EntryscapeSimpleMeterRegistry() {
		this(SimpleConfig.DEFAULT, Clock.SYSTEM);
	}

	public EntryscapeSimpleMeterRegistry(SimpleConfig config, Clock clock) {
		super(clock);

		config.requireValid();

		this.config = config;
	}

	@Override
	protected <T> Gauge newGauge(Meter.Id id, @Nullable T obj, ToDoubleFunction<T> valueFunction) {
		return new DefaultGauge<>(id, obj, valueFunction);
	}

	@Override
	protected Counter newCounter(Meter.Id id) {
		switch (config.mode()) {
			case CUMULATIVE:
				return new CumulativeCounter(id);
			case STEP:
			default:
				return new StepCounter(id, clock, config.step().toMillis());
		}
	}

	@Override
	protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig) {
		DefaultLongTaskTimer ltt = new DefaultLongTaskTimer(id, clock, getBaseTimeUnit(), distributionStatisticConfig,
				false);
		HistogramGauges.registerWithCommonFormat(ltt, this);
		return ltt;
	}

	@Override
	protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
			PauseDetector pauseDetector) {
		DistributionStatisticConfig merged = distributionStatisticConfig
				.merge(DistributionStatisticConfig.builder().expiry(config.step()).build());

		Timer timer;
		switch (config.mode()) {
			case CUMULATIVE:
				timer = new CumulativeTimer(id, clock, merged, pauseDetector, getBaseTimeUnit(), true);
				break;
			case STEP:
			default:
				timer = new StepTimer(id, clock, merged, pauseDetector, getBaseTimeUnit(), config.step().toMillis(),
						false);
				break;
		}

		HistogramGauges.registerWithCommonFormat(timer, this);

		return timer;
	}

	@Override
	protected DistributionSummary newDistributionSummary(Meter.Id id,
			DistributionStatisticConfig distributionStatisticConfig, double scale) {
		DistributionStatisticConfig merged = distributionStatisticConfig
				.merge(DistributionStatisticConfig.builder().expiry(config.step()).build());

		DistributionSummary summary;
		switch (config.mode()) {
			case CUMULATIVE:
				summary = new CumulativeDistributionSummary(id, clock, merged, scale, false);
				break;
			case STEP:
			default:
				summary = new StepDistributionSummary(id, clock, merged, scale, config.step().toMillis(), false);
				break;
		}

		HistogramGauges.registerWithCommonFormat(summary, this);

		return summary;
	}

	@Override
	protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
		return new DefaultMeter(id, type, measurements);
	}

	@Override
	protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> countFunction,
			ToDoubleFunction<T> totalTimeFunction, TimeUnit totalTimeFunctionUnit) {
		switch (config.mode()) {
			case CUMULATIVE:
				return new CumulativeFunctionTimer<>(id, obj, countFunction, totalTimeFunction, totalTimeFunctionUnit,
						getBaseTimeUnit());

			case STEP:
			default:
				return new StepFunctionTimer<>(id, clock, config.step().toMillis(), obj, countFunction,
						totalTimeFunction, totalTimeFunctionUnit, getBaseTimeUnit());
		}
	}

	@Override
	protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
		switch (config.mode()) {
			case CUMULATIVE:
				return new CumulativeFunctionCounter<>(id, obj, countFunction);

			case STEP:
			default:
				return new StepFunctionCounter<>(id, clock, config.step().toMillis(), obj, countFunction);
		}
	}

	@Override
	protected TimeUnit getBaseTimeUnit() {
		return TimeUnit.MILLISECONDS;
	}

	@Override
	protected DistributionStatisticConfig defaultHistogramConfig() {
		DistributionStatisticConfig config = DistributionStatisticConfig.builder()
				.percentiles(0.99d)
				.percentilePrecision(2)
				.minimumExpectedValue(1.0)
				.maximumExpectedValue(Double.POSITIVE_INFINITY)
				.expiry(Duration.ofMinutes(2))
				.bufferLength(3)
				.build();
		return config;
	}

	/**
	 * A very simple implementation that tries to represent the contents of the registry. The output is meant to be
	 * readable by humans, please do not parse it programmatically because the format can change.
	 *
	 * @return text representation of the meters in the registry
	 * @since 1.9.0
	 */
	@Incubating(since = "1.9.0")
	public String getMetersAsString() {
		return this.getMeters().stream().sorted(Comparator.comparing(meter -> meter.getId().getName()))
				.map(this::toString).collect(Collectors.joining("\n"));
	}

	private String toString(Meter meter) {
		Meter.Id id = meter.getId();
		String tags = id.getTags().stream().map(this::toString).collect(Collectors.joining(", "));
		String baseUnit = id.getBaseUnit();
		String meterUnitSuffix = baseUnit != null ? " " + baseUnit : "";
		String measurements = StreamSupport.stream(meter.measure().spliterator(), false)
				.map((measurement) -> toString(measurement, meterUnitSuffix)).collect(Collectors.joining(", "));
		return String.format("%s(%s)[%s]; %s", id.getName(), id.getType(), tags, measurements);
	}

	private String toString(Tag tag) {
		return String.format("%s='%s'", tag.getKey(), tag.getValue());
	}

	private String toString(Measurement measurement, String meterUnitSuffix) {
		Statistic statistic = measurement.getStatistic();
		return String.format("%s=%s%s", statistic.toString().toLowerCase(), measurement.getValue(),
				getUnitSuffix(statistic, meterUnitSuffix));
	}

	private String getUnitSuffix(Statistic statistic, String meterUnitSuffix) {
		switch (statistic) {
			case DURATION:
			case TOTAL_TIME:
			case TOTAL:
			case MAX:
			case VALUE:
				return meterUnitSuffix;

			default:
				return "";
		}
	}

}
